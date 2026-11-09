package cz.domaci.odmeny.statement;

import cz.domaci.odmeny.audit.AuditAction;
import cz.domaci.odmeny.audit.AuditService;
import cz.domaci.odmeny.completion.CompletionRepository;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.household.PeriodGate;
import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.ledger.LedgerEntryType;
import cz.domaci.odmeny.ledger.LedgerRepository;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.MoneyText;
import cz.domaci.odmeny.shared.Period;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The monthly money workflow from proposal sections 4.4, 5.3, 5.4 and 5.5.
 *
 * <p>An open month is computed live from the ledger. Closing snapshots the
 * values; a closed or paid statement is immutable until a parent explicitly
 * reopens it with a reason. Corrections are new ledger entries and never edit
 * history.
 */
@ApplicationScoped
public class StatementService {

    public static final int MAX_REASON_LENGTH = 500;
    public static final Money MAX_ADJUSTMENT = Money.czk(100_000);

    private static final String TASK_REWARD_PREFIX = "Odměna za práci: ";

    private final StatementRepository statements;
    private final LedgerRepository ledger;
    private final CompletionRepository completions;
    private final HouseholdService household;
    private final PeriodGate periodGate;
    private final AuditService audits;

    @Inject
    public StatementService(StatementRepository statements, LedgerRepository ledger,
            CompletionRepository completions, HouseholdService household, PeriodGate periodGate,
            AuditService audits) {
        this.statements = statements;
        this.ledger = ledger;
        this.completions = completions;
        this.household = household;
        this.periodGate = periodGate;
        this.audits = audits;
    }

    /**
     * A stored closed/paid snapshot, or a live view of an open month.
     */
    public Statement statement(Member member, Period period) {
        requireChild(member);
        requireNotFuture(period);

        Optional<Statement> stored = statements.find(member.id(), period);
        if (stored.isPresent() && !stored.get().isOpen()) {
            return stored.get();
        }

        Money base = stored.map(Statement::baseAllowance).orElse(member.baseAllowance());
        return computeOpen(member, period, base, stored);
    }

    public StatementDetails details(Member member, Period period) {
        Statement statement = statement(member, period);
        List<LedgerEntry> entries = ledger.listEntries(member.id(), period);
        return new StatementDetails(
                statement,
                taskLines(entries),
                adjustmentLines(entries),
                payoutLines(entries));
    }

    /**
     * Persisted statements for a child, newest first.
     */
    public List<Statement> storedStatements(Member member) {
        requireChild(member);
        return statements.listStatements(member.id()).stream()
                .sorted(Comparator.comparing(Statement::period).reversed())
                .toList();
    }

    /**
     * Adds a positive or negative correction to an open, non-future month.
     */
    public Statement addAdjustment(Member parent, Member member, Period period, Money amount, String reason) {
        requireParent(parent);
        requireChild(member);
        requireNotFuture(period);

        if (amount == null || amount.isZero()) {
            throw new InvalidStatementException("A correction must have a non-zero amount");
        }
        if (amount.compareTo(MAX_ADJUSTMENT) > 0 || amount.negate().compareTo(MAX_ADJUSTMENT) > 0) {
            throw new InvalidStatementException("A correction is too large");
        }
        String normalizedReason = requireReason(reason);

        if (!periodGate.isOpen(member.id(), period)) {
            throw new StatementClosedException("The month is closed; reopen it before adding a correction");
        }

        String id = "adjustment-" + UUID.randomUUID();
        LedgerEntry entry = new LedgerEntry(
                id,
                member.householdId(),
                member.id(),
                period,
                LedgerEntryType.ADJUSTMENT,
                amount,
                normalizedReason,
                id,
                parent.id(),
                household.now());
        ledger.addEntry(entry, audits.create(parent, member.id(), member.displayName(), period,
                AuditAction.ADJUSTMENT_ADDED, MoneyText.format(amount) + " · " + normalizedReason));
        return statement(member, period);
    }

    /**
     * Snapshots the month and marks it CLOSED. Blocked while the member has
     * pending completions in that period.
     */
    public Statement close(Member parent, Member member, Period period) {
        requireParent(parent);
        requireChild(member);
        requireNotFuture(period);

        Optional<Statement> stored = statements.find(member.id(), period);
        if (stored.isPresent() && !stored.get().isOpen()) {
            throw new StatementConflictException("The month is already closed");
        }
        if (hasPending(member, period)) {
            throw new PendingCompletionsException("The month still has pending completions");
        }

        Money base = stored.map(Statement::baseAllowance).orElse(member.baseAllowance());
        Statement open = computeOpen(member, period, base, stored);
        Instant now = household.now();

        Statement closed = new Statement(
                open.id(),
                open.householdId(),
                open.memberId(),
                open.period(),
                open.baseAllowance(),
                open.taskBonus(),
                open.adjustments(),
                open.total(),
                open.paid(),
                StatementStatus.CLOSED,
                now,
                parent.id(),
                null,
                null,
                stored.map(Statement::reopenedAt).orElse(null),
                stored.map(Statement::reopenedBy).orElse(null),
                stored.map(Statement::reopenReason).orElse(null));

        statements.close(closed, audits.create(parent, member.id(), member.displayName(), period,
                AuditAction.MONTH_CLOSED, null));
        return closed;
    }

    /**
     * Records a payout for the full remaining balance and marks the statement
     * PAID in one atomic repository operation.
     */
    public Statement markPaid(Member parent, Member member, Period period) {
        requireParent(parent);
        requireChild(member);
        requireNotFuture(period);

        Statement stored = statements.find(member.id(), period)
                .orElseThrow(() -> new InvalidStatementException("The month must be closed before a payout"));
        if (stored.isOpen()) {
            throw new InvalidStatementException("The month must be closed before a payout");
        }
        if (stored.isPaid()) {
            throw new StatementConflictException("The month is already paid");
        }

        Money balance = stored.balance();
        if (balance.compareTo(Money.ZERO) <= 0) {
            throw new NothingToPayException("There is no positive balance to pay");
        }

        Instant now = household.now();
        Money newPaid = stored.paid().plus(balance);
        String payoutId = "payout-" + stored.id() + "-" + newPaid.minor();
        LedgerEntry payout = new LedgerEntry(
                payoutId,
                member.householdId(),
                member.id(),
                period,
                LedgerEntryType.PAYOUT,
                balance.negate(),
                "Výplata za " + period.value(),
                stored.id(),
                parent.id(),
                now);

        Statement paid = new Statement(
                stored.id(),
                stored.householdId(),
                stored.memberId(),
                stored.period(),
                stored.baseAllowance(),
                stored.taskBonus(),
                stored.adjustments(),
                stored.total(),
                newPaid,
                StatementStatus.PAID,
                stored.closedAt(),
                stored.closedBy(),
                now,
                parent.id(),
                stored.reopenedAt(),
                stored.reopenedBy(),
                stored.reopenReason());

        statements.markPaid(paid, payout, audits.create(parent, member.id(), member.displayName(), period,
                AuditAction.MONTH_PAID, MoneyText.format(balance)));
        return paid;
    }

    /**
     * Explicitly reopens a closed or paid month. The stored base snapshot is
     * kept, and later corrections become new ledger entries.
     */
    public Statement reopen(Member parent, Member member, Period period, String reason) {
        requireParent(parent);
        requireChild(member);
        requireNotFuture(period);

        Statement stored = statements.find(member.id(), period)
                .orElseThrow(() -> new InvalidStatementException("The month was never closed"));
        if (stored.isOpen()) {
            throw new StatementConflictException("The month is already open");
        }
        String normalizedReason = requireReason(reason);
        Instant now = household.now();

        Statement reopened = new Statement(
                stored.id(),
                stored.householdId(),
                stored.memberId(),
                stored.period(),
                stored.baseAllowance(),
                stored.taskBonus(),
                stored.adjustments(),
                stored.total(),
                stored.paid(),
                StatementStatus.OPEN,
                stored.closedAt(),
                stored.closedBy(),
                stored.paidAt(),
                stored.paidBy(),
                now,
                parent.id(),
                normalizedReason);

        statements.reopen(reopened, audits.create(parent, member.id(), member.displayName(), period,
                AuditAction.MONTH_REOPENED, normalizedReason));
        return reopened;
    }

    private Statement computeOpen(Member member, Period period, Money base, Optional<Statement> stored) {
        Money taskBonus = Money.ZERO;
        Money adjustments = Money.ZERO;
        Money paid = Money.ZERO;

        for (LedgerEntry entry : ledger.listEntries(member.id(), period)) {
            switch (entry.type()) {
                case TASK_REWARD -> taskBonus = taskBonus.plus(entry.amount());
                case ADJUSTMENT, REVERSAL -> adjustments = adjustments.plus(entry.amount());
                case PAYOUT -> paid = paid.minus(entry.amount());
                case BASE_ALLOWANCE -> {
                    // The base is a member setting, snapshotted on close. A
                    // BASE_ALLOWANCE entry would double count it.
                }
            }
        }

        Money total = base.plus(taskBonus).plus(adjustments);
        // A reopened month keeps its audit trail even though the money is
        // recomputed live from the ledger.
        Instant reopenedAt = stored.map(Statement::reopenedAt).orElse(null);
        String reopenedBy = stored.map(Statement::reopenedBy).orElse(null);
        String reopenReason = stored.map(Statement::reopenReason).orElse(null);

        return new Statement(
                Statement.idFor(member.id(), period),
                member.householdId(),
                member.id(),
                period,
                base,
                taskBonus,
                adjustments,
                total,
                paid,
                StatementStatus.OPEN,
                null,
                null,
                null,
                null,
                reopenedAt,
                reopenedBy,
                reopenReason);
    }

    private static List<StatementDetails.StatementLine> taskLines(List<LedgerEntry> entries) {
        Map<String, GroupedLine> grouped = new LinkedHashMap<>();
        for (LedgerEntry entry : entries) {
            if (entry.type() != LedgerEntryType.TASK_REWARD) {
                continue;
            }
            String taskName = taskName(entry.description());
            grouped.computeIfAbsent(taskName, name -> new GroupedLine())
                    .add(entry.amount());
        }

        List<StatementDetails.StatementLine> lines = new ArrayList<>();
        grouped.forEach((taskName, group) -> lines.add(new StatementDetails.StatementLine(
                taskName, "", group.amount, group.count)));
        return lines;
    }

    private static List<StatementDetails.StatementLine> adjustmentLines(List<LedgerEntry> entries) {
        List<StatementDetails.StatementLine> lines = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry.type() == LedgerEntryType.ADJUSTMENT || entry.type() == LedgerEntryType.REVERSAL) {
                lines.add(new StatementDetails.StatementLine(
                        entry.type().label(), entry.description(), entry.amount(), 1));
            }
        }
        return lines;
    }

    private static List<StatementDetails.StatementLine> payoutLines(List<LedgerEntry> entries) {
        List<StatementDetails.StatementLine> lines = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry.type() == LedgerEntryType.PAYOUT) {
                lines.add(new StatementDetails.StatementLine(
                        entry.type().label(), entry.description(), entry.amount().negate(), 1));
            }
        }
        return lines;
    }

    private static String taskName(String description) {
        return description.startsWith(TASK_REWARD_PREFIX)
                ? description.substring(TASK_REWARD_PREFIX.length())
                : description;
    }

    private boolean hasPending(Member member, Period period) {
        return completions.listPending().stream()
                .anyMatch(pending -> pending.memberId().equals(member.id())
                        && pending.period().equals(period));
    }

    private void requireNotFuture(Period period) {
        if (period.compareTo(household.currentPeriod()) > 0) {
            throw new InvalidStatementException("Future months cannot be changed");
        }
    }

    private static void requireParent(Member member) {
        if (!member.isParent()) {
            throw new InvalidStatementException("Only a parent can manage statements");
        }
    }

    private static void requireChild(Member member) {
        if (member.isParent()) {
            throw new InvalidStatementException("Statements exist only for children");
        }
    }

    private static String requireReason(String reason) {
        if (reason == null) {
            throw new InvalidStatementException("A reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidStatementException("A reason is required");
        }
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new InvalidStatementException("The reason is too long");
        }
        return trimmed;
    }

    private static final class GroupedLine {

        private Money amount = Money.ZERO;
        private int count;

        void add(Money value) {
            amount = amount.plus(value);
            count++;
        }
    }
}

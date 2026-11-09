package cz.domaci.odmeny.statement;

import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionService;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.household.MonthClosedException;
import cz.domaci.odmeny.ledger.EarningsService;
import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.ledger.LedgerEntryType;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.member.MemberRole;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.testsupport.InMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class StatementServiceTest {

    @Inject
    InMemoryStore store;

    @Inject
    CompletionService completions;

    @Inject
    StatementService statements;

    @Inject
    StatementRepository statementRepository;

    @Inject
    MemberService members;

    @Inject
    HouseholdService household;

    @Inject
    EarningsService earnings;

    private Member child;
    private Member sibling;
    private Member parent;
    private Period current;

    @BeforeEach
    void setUp() {
        store.reset();
        child = member("dev-child-1");
        sibling = member("dev-child-2");
        parent = member("dev-parent-1");
        current = household.currentPeriod();
    }

    @Test
    void openStatementCombinesBaseRewardsAndCorrections() {
        approve("odpadky");
        approve("odpadky");
        statements.addAdjustment(parent, child, current, Money.czk(20), "Bonus");

        Statement statement = statements.statement(child, current);

        assertEquals(StatementStatus.OPEN, statement.status());
        assertEquals(Money.czk(50), statement.baseAllowance());
        assertEquals(Money.czk(20), statement.taskBonus());
        assertEquals(Money.czk(20), statement.adjustments());
        assertEquals(Money.czk(90), statement.total());
        assertEquals(Money.ZERO, statement.paid());
        assertEquals(Money.czk(90), statement.balance());

        StatementDetails details = statements.details(child, current);
        assertEquals(1, details.taskLines().size());
        assertEquals("Odpadky", details.taskLines().get(0).label());
        assertEquals(2, details.taskLines().get(0).count());
        assertEquals(Money.czk(20), details.taskLines().get(0).amount());
        assertEquals(1, details.adjustmentLines().size());
        assertEquals("Bonus", details.adjustmentLines().get(0).description());
        assertTrue(details.payoutLines().isEmpty());
    }

    @Test
    void closeSnapshotsTheMonth() {
        approve("odpadky");
        statements.addAdjustment(parent, child, current, Money.czk(20), "Bonus");

        Statement closed = statements.close(parent, child, current);

        assertEquals(StatementStatus.CLOSED, closed.status());
        assertEquals(parent.id(), closed.closedBy());
        assertEquals(Money.czk(50), closed.baseAllowance());
        assertEquals(Money.czk(10), closed.taskBonus());
        assertEquals(Money.czk(20), closed.adjustments());
        assertEquals(Money.czk(80), closed.total());
        assertEquals(Money.ZERO, closed.paid());
        assertTrue(statementRepository.find(child.id(), current).isPresent());
    }

    @Test
    void closeIsBlockedByPendingCompletions() {
        completions.logChore(child, "odpadky", null);

        assertThrows(PendingCompletionsException.class,
                () -> statements.close(parent, child, current));
        assertTrue(statementRepository.find(child.id(), current).isEmpty());
    }

    @Test
    void closeRejectsFutureMonths() {
        assertThrows(InvalidStatementException.class,
                () -> statements.close(parent, child, current.next()));
    }

    @Test
    void correctionsAreBlockedInAClosedMonth() {
        statements.close(parent, child, current);

        assertThrows(StatementClosedException.class,
                () -> statements.addAdjustment(parent, child, current, Money.czk(10), "Pozdní bonus"));
    }

    @Test
    void markPaidWritesAPayoutAndClearsTheBalance() {
        approve("odpadky");
        statements.close(parent, child, current);

        Statement paid = statements.markPaid(parent, child, current);

        assertEquals(StatementStatus.PAID, paid.status());
        assertEquals(Money.czk(60), paid.paid());
        assertEquals(Money.ZERO, paid.balance());

        List<LedgerEntry> payouts = store.ledger(child.id(), current).stream()
                .filter(entry -> entry.type() == LedgerEntryType.PAYOUT)
                .toList();
        assertEquals(1, payouts.size());
        assertEquals(Money.czk(-60), payouts.get(0).amount());
        assertEquals(paid.id(), payouts.get(0).referenceId());

        // A payout reduces the balance due, not the amount earned on the home page.
        assertEquals(Money.czk(60), earnings.currentMonth(child).total());

        assertThrows(StatementConflictException.class,
                () -> statements.markPaid(parent, child, current));
    }

    @Test
    void markPaidRequiresAClosedMonth() {
        assertThrows(InvalidStatementException.class,
                () -> statements.markPaid(parent, child, current));
    }

    @Test
    void markPaidRejectsAZeroBalance() {
        statements.addAdjustment(parent, child, current, Money.czk(-50), "Snížení základu");
        statements.close(parent, child, current);

        assertThrows(NothingToPayException.class,
                () -> statements.markPaid(parent, child, current));
    }

    @Test
    void reopenKeepsTheBaseSnapshotAndRecloseRecomputes() {
        approve("odpadky");
        statements.close(parent, child, current);

        Member raisedBase = new Member(child.id(), child.householdId(), child.cognitoSub(),
                child.displayName(), MemberRole.CHILD, Money.czk(100), true);
        store.putMember(raisedBase);

        Statement reopened = statements.reopen(parent, raisedBase, current, "Chyba v základu");
        assertEquals(StatementStatus.OPEN, reopened.status());
        assertEquals("Chyba v základu", reopened.reopenReason());

        Statement open = statements.statement(raisedBase, current);
        assertEquals(Money.czk(50), open.baseAllowance());
        assertEquals(Money.czk(60), open.total());

        statements.addAdjustment(parent, raisedBase, current, Money.czk(5), "Dodatečný bonus");
        Statement reclosed = statements.close(parent, raisedBase, current);

        assertEquals(Money.czk(50), reclosed.baseAllowance());
        assertEquals(Money.czk(10), reclosed.taskBonus());
        assertEquals(Money.czk(5), reclosed.adjustments());
        assertEquals(Money.czk(65), reclosed.total());
        assertEquals("Chyba v základu", reclosed.reopenReason());
    }

    @Test
    void reopenRequiresAReason() {
        statements.close(parent, child, current);

        assertThrows(InvalidStatementException.class,
                () -> statements.reopen(parent, child, current, "   "));
    }

    @Test
    void reopenRequiresAClosedMonth() {
        assertThrows(InvalidStatementException.class,
                () -> statements.reopen(parent, child, current, "Není co otevírat"));
    }

    @Test
    void lateWorkGoesToTheNextOpenMonth() {
        statements.close(parent, child, current);

        Completion late = completions.logChore(child, "odpadky", null);

        assertEquals(current.next(), late.period());
        assertEquals(current.next(), store.pendingItems().get(0).period());
    }

    @Test
    void approvalIsBlockedWhenTheMonthWasClosedBehindTheService() {
        Completion pending = completions.logChore(child, "odpadky", null);
        Statement open = statements.statement(child, current);
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
                household.now(),
                parent.id(),
                null,
                null,
                null,
                null,
                null);
        statementRepository.close(closed);

        assertThrows(MonthClosedException.class,
                () -> completions.approve(parent, pending.id()));
        assertFalse(store.ledger(child.id(), current).stream()
                .anyMatch(entry -> entry.type() == LedgerEntryType.TASK_REWARD));
    }

    @Test
    void statementsAreOnlyForChildren() {
        assertThrows(InvalidStatementException.class,
                () -> statements.statement(parent, current));
        assertThrows(InvalidStatementException.class,
                () -> statements.statement(child, current.next()));
    }

    private void approve(String taskId) {
        Completion pending = completions.logChore(child, taskId, null);
        completions.approve(parent, pending.id());
    }

    private Member member(String cognitoSub) {
        return members.findByCognitoSub(cognitoSub).orElseThrow();
    }
}

package cz.domaci.odmeny.web;

import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionStatus;
import cz.domaci.odmeny.completion.PendingCompletion;
import cz.domaci.odmeny.ledger.EarningsService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.statement.Statement;
import cz.domaci.odmeny.statement.StatementDetails;
import cz.domaci.odmeny.statement.StatementStatus;
import cz.domaci.odmeny.task.Task;

import java.time.ZoneId;
import java.util.List;

/**
 * View models passed to Qute. Templates never call domain formatting methods
 * directly.
 */
public final class Views {

    private Views() {
    }

    public static TaskView task(Task task) {
        return new TaskView(
                task.id(),
                task.name(),
                task.description(),
                Formats.money(task.reward()),
                task.requiresApproval());
    }

    public static CompletionView completion(Completion completion, ZoneId zone) {
        return new CompletionView(
                completion.id(),
                completion.taskName(),
                Formats.money(completion.reward()),
                completion.status().label(),
                statusKind(completion.status()),
                Formats.dateTime(completion.completedAt(), zone),
                completion.note(),
                completion.rejectionReason());
    }

    public static PendingView pending(PendingCompletion pending, ZoneId zone) {
        return new PendingView(
                pending.completionId(),
                pending.memberName(),
                pending.taskName(),
                Formats.money(pending.reward()),
                Formats.dateTime(pending.completedAt(), zone),
                pending.note());
    }

    public static MemberTotalView total(Member member, EarningsService.EarningsSummary summary) {
        return new MemberTotalView(
                member.id(),
                member.displayName(),
                Formats.money(summary.base()),
                Formats.money(summary.bonus()),
                Formats.money(summary.total()));
    }

    public static PeriodView period(Period period, List<CompletionView> completions, ZoneId zone) {
        return new PeriodView(Formats.period(period, zone), completions);
    }

    public static StatementView statement(Statement statement, String memberName, ZoneId zone) {
        return new StatementView(
                statement.id(),
                statement.memberId(),
                memberName,
                statement.period().value(),
                Formats.period(statement.period(), zone),
                Formats.money(statement.baseAllowance()),
                Formats.money(statement.taskBonus()),
                Formats.money(statement.adjustments()),
                Formats.money(statement.total()),
                Formats.money(statement.paid()),
                Formats.money(statement.balance()),
                statement.status().label(),
                statementStatusKind(statement.status()),
                statement.isOpen(),
                statement.isClosed(),
                statement.isPaid(),
                statement.closedAt() == null ? "" : Formats.dateTime(statement.closedAt(), zone),
                statement.paidAt() == null ? "" : Formats.dateTime(statement.paidAt(), zone),
                statement.reopenReason() == null ? "" : statement.reopenReason());
    }

    public static StatementOverviewRow overviewRow(Member member, Statement current, Statement previous,
            ZoneId zone) {
        return new StatementOverviewRow(
                member.id(),
                member.displayName(),
                statement(current, member.displayName(), zone),
                statement(previous, member.displayName(), zone));
    }

    public static StatementDetailView statementDetail(StatementDetails details, String memberName, ZoneId zone) {
        return new StatementDetailView(
                statement(details.statement(), memberName, zone),
                details.taskLines().stream().map(Views::statementLine).toList(),
                details.adjustmentLines().stream().map(Views::statementLine).toList(),
                details.payoutLines().stream().map(Views::statementLine).toList());
    }

    private static StatementLineView statementLine(StatementDetails.StatementLine line) {
        return new StatementLineView(
                line.label(),
                line.description(),
                Formats.money(line.amount()),
                line.count());
    }

    private static String statementStatusKind(StatementStatus status) {
        return switch (status) {
            case OPEN -> "open";
            case CLOSED -> "closed";
            case PAID -> "paid";
        };
    }

    private static String statusKind(CompletionStatus status) {
        return switch (status) {
            case PENDING -> "pending";
            case APPROVED -> "approved";
            case REJECTED -> "rejected";
            case REVERSED -> "reversed";
        };
    }

    public record TaskView(
            String id,
            String name,
            String description,
            String rewardText,
            boolean requiresApproval) {
    }

    public record CompletionView(
            String id,
            String taskName,
            String rewardText,
            String statusLabel,
            String statusKind,
            String whenText,
            String note,
            String rejectionReason) {
    }

    public record PendingView(
            String completionId,
            String memberName,
            String taskName,
            String rewardText,
            String whenText,
            String note) {
    }

    public record MemberTotalView(
            String memberId,
            String displayName,
            String baseText,
            String bonusText,
            String totalText) {
    }

    public record PeriodView(String label, List<CompletionView> completions) {
    }

    public record StatementView(
            String id,
            String memberId,
            String memberName,
            String periodValue,
            String periodLabel,
            String baseText,
            String taskBonusText,
            String adjustmentsText,
            String totalText,
            String paidText,
            String balanceText,
            String statusLabel,
            String statusKind,
            boolean open,
            boolean closed,
            boolean paid,
            String closedAtText,
            String paidAtText,
            String reopenReason) {
    }

    public record StatementOverviewRow(
            String memberId,
            String memberName,
            StatementView current,
            StatementView previous) {
    }

    public record StatementDetailView(
            StatementView statement,
            List<StatementLineView> taskLines,
            List<StatementLineView> adjustmentLines,
            List<StatementLineView> payoutLines) {
    }

    public record StatementLineView(
            String label,
            String description,
            String amountText,
            int count) {
    }
}

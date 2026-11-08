package cz.domaci.odmeny.web;

import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionStatus;
import cz.domaci.odmeny.completion.PendingCompletion;
import cz.domaci.odmeny.ledger.EarningsService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.shared.Period;
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
}

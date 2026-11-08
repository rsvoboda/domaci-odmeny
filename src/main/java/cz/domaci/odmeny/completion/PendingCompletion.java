package cz.domaci.odmeny.completion;

import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;

import java.time.Instant;
import java.util.Objects;

/**
 * A projection of a pending completion used by the parent approval queue.
 */
public record PendingCompletion(
        String completionId,
        String householdId,
        String memberId,
        String memberName,
        String taskId,
        String taskName,
        Money reward,
        Instant completedAt,
        Period period,
        String note,
        Instant createdAt) {

    public PendingCompletion {
        Objects.requireNonNull(completionId, "completionId");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(memberName, "memberName");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(reward, "reward");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}

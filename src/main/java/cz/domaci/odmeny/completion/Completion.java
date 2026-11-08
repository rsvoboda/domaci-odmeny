package cz.domaci.odmeny.completion;

import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;

import java.time.Instant;
import java.util.Objects;

/**
 * A child's claim that a chore was completed.
 *
 * <p>The task name and reward are snapshots. Renaming a chore or changing its
 * reward later must not rewrite history.
 */
public record Completion(
        String id,
        String householdId,
        String memberId,
        String taskId,
        String taskName,
        Instant completedAt,
        Instant createdAt,
        Period period,
        CompletionStatus status,
        Money reward,
        String note,
        String approvedBy,
        Instant approvedAt,
        String rejectionReason) {

    public static final int MAX_TEXT_LENGTH = 500;

    public Completion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reward, "reward");
        if (reward.isNegative()) {
            throw new IllegalArgumentException("reward must not be negative");
        }
        if (note != null && note.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("note is too long");
        }
        if (rejectionReason != null && rejectionReason.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("rejection reason is too long");
        }
    }

    public boolean isPending() {
        return status.isPending();
    }
}

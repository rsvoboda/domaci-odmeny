package cz.domaci.odmeny.completion;

import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.household.MonthClosedException;
import cz.domaci.odmeny.household.PeriodGate;
import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.ledger.LedgerEntryType;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.task.Task;
import cz.domaci.odmeny.task.TaskService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The child logging and parent approval workflow from ADR-0007.
 *
 * <p>All writes are delegated to the repository so that the completion, the
 * pending queue and the ledger stay consistent.
 */
@ApplicationScoped
public class CompletionService {

    private final CompletionRepository completions;
    private final TaskService tasks;
    private final HouseholdService household;
    private final PeriodGate periodGate;

    @Inject
    public CompletionService(CompletionRepository completions, TaskService tasks, HouseholdService household,
            PeriodGate periodGate) {
        this.completions = completions;
        this.tasks = tasks;
        this.household = household;
        this.periodGate = periodGate;
    }

    /**
     * Logs a completed chore for a child.
     *
     * <p>The reward is snapshotted on the completion. Chores that do not require
     * approval are credited immediately in the same atomic write.
     */
    public Completion logChore(Member child, String taskId, String note) {
        if (child.isParent()) {
            throw new InvalidCompletionException("Parents cannot log chores on behalf of children");
        }

        Task task = tasks.findActive(taskId)
                .orElseThrow(() -> new TaskNotAvailableException(taskId));

        Instant now = household.now();
        String normalizedNote = normalize(note);
        // Late work after a close belongs to the next open month (proposal 5.5).
        Period period = periodGate.nextOpen(child.id(), Period.from(now, household.zone()));
        String completionId = UUID.randomUUID().toString();

        boolean requiresApproval = task.requiresApproval();
        Completion completion = new Completion(
                completionId,
                household.id(),
                child.id(),
                task.id(),
                task.name(),
                now,
                now,
                period,
                requiresApproval ? CompletionStatus.PENDING : CompletionStatus.APPROVED,
                task.reward(),
                normalizedNote,
                requiresApproval ? null : child.id(),
                requiresApproval ? null : now,
                null);

        if (requiresApproval) {
            PendingCompletion pending = new PendingCompletion(
                    completionId,
                    household.id(),
                    child.id(),
                    child.displayName(),
                    task.id(),
                    task.name(),
                    task.reward(),
                    now,
                    period,
                    normalizedNote,
                    now);
            completions.createPending(completion, pending);
        } else {
            completions.createApproved(completion, rewardEntry(completion, child.id(), now));
        }

        return completion;
    }

    public List<Completion> currentMonth(Member member) {
        return sorted(completions.listCompletions(member.id(), household.currentPeriod()));
    }

    public List<Completion> history(Member member, Period period) {
        return sorted(completions.listCompletions(member.id(), period));
    }

    public List<PendingCompletion> pending() {
        return completions.listPending().stream()
                .sorted(Comparator.comparing(PendingCompletion::createdAt))
                .toList();
    }

    public Completion approve(Member parent, String completionId) {
        requireParent(parent);
        PendingCompletion pending = completions.findPending(completionId)
                .orElseThrow(() -> new CompletionConflictException(
                        "Completion is no longer pending: " + completionId));

        if (!periodGate.isOpen(pending.memberId(), pending.period())) {
            throw new MonthClosedException(
                    "The completion belongs to a closed month: " + pending.period().value());
        }

        Instant now = household.now();
        Completion approved = new Completion(
                pending.completionId(),
                pending.householdId(),
                pending.memberId(),
                pending.taskId(),
                pending.taskName(),
                pending.completedAt(),
                pending.createdAt(),
                pending.period(),
                CompletionStatus.APPROVED,
                pending.reward(),
                pending.note(),
                parent.id(),
                now,
                null);

        completions.approve(approved, rewardEntry(approved, parent.id(), now));
        return approved;
    }

    public Completion reject(Member parent, String completionId, String reason) {
        requireParent(parent);
        String normalizedReason = normalize(reason);
        if (normalizedReason == null) {
            throw new InvalidCompletionException("A rejection reason is required");
        }

        PendingCompletion pending = completions.findPending(completionId)
                .orElseThrow(() -> new CompletionConflictException(
                        "Completion is no longer pending: " + completionId));

        Instant now = household.now();
        Completion rejected = new Completion(
                pending.completionId(),
                pending.householdId(),
                pending.memberId(),
                pending.taskId(),
                pending.taskName(),
                pending.completedAt(),
                pending.createdAt(),
                pending.period(),
                CompletionStatus.REJECTED,
                pending.reward(),
                pending.note(),
                parent.id(),
                now,
                normalizedReason);

        completions.reject(rejected);
        return rejected;
    }

    public Optional<PendingCompletion> findPending(String completionId) {
        return completions.findPending(completionId);
    }

    private static List<Completion> sorted(List<Completion> list) {
        return list.stream()
                .sorted(Comparator.comparing(Completion::completedAt).reversed())
                .toList();
    }

    private static void requireParent(Member member) {
        if (!member.isParent()) {
            throw new InvalidCompletionException("Only a parent can review completions");
        }
    }

    /**
     * A deterministic ledger id guarantees that one completion can create at
     * most one reward entry.
     */
    private static LedgerEntry rewardEntry(Completion completion, String createdBy, Instant createdAt) {
        return new LedgerEntry(
                "reward-" + completion.id(),
                completion.householdId(),
                completion.memberId(),
                completion.period(),
                LedgerEntryType.TASK_REWARD,
                completion.reward(),
                "Odměna za práci: " + completion.taskName(),
                completion.id(),
                createdBy,
                createdAt);
    }

    private static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > Completion.MAX_TEXT_LENGTH) {
            throw new InvalidCompletionException("Text is too long");
        }
        return trimmed;
    }
}

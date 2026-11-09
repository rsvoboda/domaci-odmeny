package cz.domaci.odmeny.completion;

import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.shared.Period;

import java.util.List;
import java.util.Optional;

/**
 * Storage-agnostic access to completions and the parent approval queue.
 *
 * <p>The create/approve/reject operations must be atomic with the pending queue
 * and the ledger. Implementations use DynamoDB transactions for this.
 */
public interface CompletionRepository {

    /**
     * Stores a completion that requires approval and adds it to the queue.
     */
    void createPending(Completion completion, PendingCompletion pending);

    /**
     * Stores an already approved completion and its reward ledger entry.
     */
    void createApproved(Completion completion, LedgerEntry ledgerEntry);

    /**
     * Atomically approves a pending completion, writes the reward ledger entry
     * and removes the queue item.
     *
     * @throws CompletionConflictException when the completion is no longer pending
     */
    void approve(Completion completion, LedgerEntry ledgerEntry);

    /**
     * Atomically rejects a pending completion and removes the queue item.
     *
     * @throws CompletionConflictException when the completion is no longer pending
     */
    void reject(Completion completion);

    List<Completion> listCompletions(String memberId, Period period);

    /**
     * All completions for a member across every period. Used by the export.
     */
    List<Completion> listCompletions(String memberId);

    List<PendingCompletion> listPending();

    Optional<PendingCompletion> findPending(String completionId);
}

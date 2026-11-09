package cz.domaci.odmeny.completion;

import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.shared.Period;

import java.util.List;
import java.util.Optional;

/**
 * Storage-agnostic access to completions and the parent approval queue.
 *
 * <p>The create/approve/reject operations must be atomic with the pending queue,
 * the ledger and the audit trail. Implementations use DynamoDB transactions for
 * this.
 */
public interface CompletionRepository {

    /**
     * Stores a completion that requires approval, adds it to the queue and
     * writes the audit event.
     */
    void createPending(Completion completion, PendingCompletion pending, AuditEvent audit);

    /**
     * Stores an already approved completion, its reward ledger entry and the
     * audit event.
     */
    void createApproved(Completion completion, LedgerEntry ledgerEntry, AuditEvent audit);

    /**
     * Atomically approves a pending completion, writes the reward ledger entry,
     * removes the queue item and writes the audit event.
     *
     * @throws CompletionConflictException when the completion is no longer pending
     */
    void approve(Completion completion, LedgerEntry ledgerEntry, AuditEvent audit);

    /**
     * Atomically rejects a pending completion, removes the queue item and
     * writes the audit event.
     *
     * @throws CompletionConflictException when the completion is no longer pending
     */
    void reject(Completion completion, AuditEvent audit);

    List<Completion> listCompletions(String memberId, Period period);

    /**
     * All completions for a member across every period. Used by the export.
     */
    List<Completion> listCompletions(String memberId);

    List<PendingCompletion> listPending();

    Optional<PendingCompletion> findPending(String completionId);
}

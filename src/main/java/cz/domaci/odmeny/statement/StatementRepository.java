package cz.domaci.odmeny.statement;

import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.shared.Period;

import java.util.List;
import java.util.Optional;

/**
 * Storage-agnostic access to persisted statements.
 *
 * <p>Open months that were never closed are not stored. A statement is stored
 * when it is closed, paid, or explicitly reopened.
 */
public interface StatementRepository {

    Optional<Statement> find(String memberId, Period period);

    List<Statement> listStatements(String memberId);

    /**
     * Creates a closed statement, or replaces a reopened OPEN statement.
     *
     * @throws StatementConflictException when the stored statement is CLOSED or PAID
     */
    void close(Statement statement);

    /**
     * Replaces a CLOSED or PAID statement with an OPEN one, keeping its base
     * snapshot and audit fields.
     *
     * @throws StatementConflictException when the stored statement is missing or already OPEN
     */
    void reopen(Statement statement);

    /**
     * Atomically writes the payout ledger entry and marks the statement PAID.
     *
     * @throws StatementConflictException when the statement is no longer CLOSED
     */
    void markPaid(Statement statement, LedgerEntry payout);
}

package cz.domaci.odmeny.ledger;

import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.shared.Period;

import java.util.List;

/**
 * Read access to the append-only ledger plus the only write operation: appending
 * a new immutable entry. There is intentionally no update or delete operation.
 */
public interface LedgerRepository {

    List<LedgerEntry> listEntries(String memberId, Period period);

    /**
     * All entries for a member across every period. Used by the export.
     */
    List<LedgerEntry> listEntries(String memberId);

    /**
     * Appends a new entry and its audit event in one atomic write.
     *
     * @throws IllegalStateException when the entry id already exists
     */
    void addEntry(LedgerEntry entry, AuditEvent audit);
}

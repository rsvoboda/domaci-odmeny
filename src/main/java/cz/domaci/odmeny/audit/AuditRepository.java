package cz.domaci.odmeny.audit;

import cz.domaci.odmeny.shared.Period;

import java.util.List;

/**
 * Storage-agnostic access to the audit trail.
 *
 * <p>There is intentionally no update or delete operation: an audit event is
 * immutable once written.
 */
public interface AuditRepository {

    /**
     * Appends an event outside a larger transaction. Used only for events that
     * are not part of a state change.
     */
    void append(AuditEvent event);

    /**
     * Events for one accounting period, oldest first.
     */
    List<AuditEvent> listByPeriod(Period period);

    /**
     * Every event from {@code from} to {@code to} inclusive, oldest first. Used
     * by the full export. Implementations query only the month partitions in the
     * range instead of scanning the whole table.
     */
    List<AuditEvent> listBetween(Period from, Period to);
}

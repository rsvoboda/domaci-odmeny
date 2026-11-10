package cz.domaci.odmeny.household;

import cz.domaci.odmeny.audit.AuditEvent;

import java.util.Optional;

/**
 * Storage-agnostic access to household settings.
 */
public interface HouseholdRepository {

    Optional<Household> findById(String id);

    void put(Household household);

    /**
     * Updates an existing household and writes the audit event in the same
     * transaction.
     *
     * @throws IllegalStateException when the household no longer exists
     */
    void update(Household household, AuditEvent audit);
}

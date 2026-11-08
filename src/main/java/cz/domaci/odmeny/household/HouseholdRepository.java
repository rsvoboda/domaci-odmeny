package cz.domaci.odmeny.household;

import java.util.Optional;

/**
 * Storage-agnostic access to household settings.
 */
public interface HouseholdRepository {

    Optional<Household> findById(String id);

    void put(Household household);
}

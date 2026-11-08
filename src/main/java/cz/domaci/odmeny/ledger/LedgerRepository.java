package cz.domaci.odmeny.ledger;

import cz.domaci.odmeny.shared.Period;

import java.util.List;

/**
 * Read access to the append-only ledger. There is intentionally no update or
 * delete operation.
 */
public interface LedgerRepository {

    List<LedgerEntry> listEntries(String memberId, Period period);
}

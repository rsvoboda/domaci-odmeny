package cz.domaci.odmeny.ledger;

import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable money movement. Entries are never edited or deleted.
 *
 * <p>{@code amount} is signed: rewards and positive adjustments are positive,
 * payouts and reversals are negative.
 */
public record LedgerEntry(
        String id,
        String householdId,
        String memberId,
        Period period,
        LedgerEntryType type,
        Money amount,
        String description,
        String referenceId,
        String createdBy,
        Instant createdAt) {

    public LedgerEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}

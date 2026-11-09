package cz.domaci.odmeny.statement;

import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;

import java.time.Instant;
import java.util.Objects;

/**
 * A monthly snapshot for one child.
 *
 * <p>Open months are computed live from the ledger and are not persisted.
 * Closing stores the snapshot; the statement is then immutable until a parent
 * reopens it with an audit reason. Reopening keeps the base amount snapshot so
 * a later close cannot silently pick up a changed base.
 */
public record Statement(
        String id,
        String householdId,
        String memberId,
        Period period,
        Money baseAllowance,
        Money taskBonus,
        Money adjustments,
        Money total,
        Money paid,
        StatementStatus status,
        Instant closedAt,
        String closedBy,
        Instant paidAt,
        String paidBy,
        Instant reopenedAt,
        String reopenedBy,
        String reopenReason) {

    public Statement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(baseAllowance, "baseAllowance");
        Objects.requireNonNull(taskBonus, "taskBonus");
        Objects.requireNonNull(adjustments, "adjustments");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(paid, "paid");
        Objects.requireNonNull(status, "status");

        if (baseAllowance.isNegative()) {
            throw new IllegalArgumentException("baseAllowance must not be negative");
        }
        if (taskBonus.isNegative()) {
            throw new IllegalArgumentException("taskBonus must not be negative");
        }
        if (paid.isNegative()) {
            throw new IllegalArgumentException("paid must not be negative");
        }
        Money expected = baseAllowance.plus(taskBonus).plus(adjustments);
        if (!expected.equals(total)) {
            throw new IllegalArgumentException("total must equal base + taskBonus + adjustments");
        }
        if ((status == StatementStatus.CLOSED || status == StatementStatus.PAID) && closedAt == null) {
            throw new IllegalArgumentException("a closed statement must have closedAt");
        }
        if (status == StatementStatus.PAID && paidAt == null) {
            throw new IllegalArgumentException("a paid statement must have paidAt");
        }
    }

    /**
     * Statements are unique per member and period. The deterministic id also
     * makes a repeated close or payout fail instead of creating a duplicate.
     */
    public static String idFor(String memberId, Period period) {
        return "statement-" + memberId + "-" + period.value();
    }

    public Money balance() {
        return total.minus(paid);
    }

    public boolean isOpen() {
        return status == StatementStatus.OPEN;
    }

    public boolean isClosed() {
        return status == StatementStatus.CLOSED;
    }

    public boolean isPaid() {
        return status == StatementStatus.PAID;
    }
}

package cz.domaci.odmeny.statement;

import cz.domaci.odmeny.shared.Money;

import java.util.List;
import java.util.Objects;

/**
 * A statement plus the ledger lines that explain it.
 */
public record StatementDetails(
        Statement statement,
        List<StatementLine> taskLines,
        List<StatementLine> adjustmentLines,
        List<StatementLine> payoutLines) {

    public StatementDetails {
        Objects.requireNonNull(statement, "statement");
        taskLines = List.copyOf(taskLines);
        adjustmentLines = List.copyOf(adjustmentLines);
        payoutLines = List.copyOf(payoutLines);
    }

    /**
     * One grouped line, for example {@code Odpadky × 4} or a single adjustment.
     */
    public record StatementLine(String label, String description, Money amount, int count) {

        public StatementLine {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(amount, "amount");
            if (count < 1) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
    }
}

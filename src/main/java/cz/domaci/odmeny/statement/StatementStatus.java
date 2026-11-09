package cz.domaci.odmeny.statement;

/**
 * Lifecycle of a monthly statement.
 */
public enum StatementStatus {

    OPEN("Otevřený"),
    CLOSED("Uzavřený"),
    PAID("Vyplacený");

    private final String label;

    StatementStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

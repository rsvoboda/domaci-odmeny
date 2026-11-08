package cz.domaci.odmeny.completion;

/**
 * Lifecycle of a child's chore completion.
 */
public enum CompletionStatus {

    PENDING("Čeká na schválení"),
    APPROVED("Schváleno"),
    REJECTED("Zamítnuto"),
    REVERSED("Stornováno");

    private final String label;

    CompletionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isPending() {
        return this == PENDING;
    }
}

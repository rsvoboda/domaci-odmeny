package cz.domaci.odmeny.audit;

/**
 * Every state change that matters for trust is recorded as one of these
 * actions. The label is shown in the parent-only audit view.
 */
public enum AuditAction {

    CHORE_LOGGED("Práce zapsána", "logged"),
    CHORE_AUTO_APPROVED("Práce automaticky schválena", "approved"),
    CHORE_APPROVED("Práce schválena", "approved"),
    CHORE_REJECTED("Práce zamítnuta", "rejected"),
    ADJUSTMENT_ADDED("Úprava výpisu", "adjusted"),
    MONTH_CLOSED("Měsíc uzavřen", "closed"),
    MONTH_PAID("Měsíc vyplacen", "paid"),
    MONTH_REOPENED("Měsíc znovu otevřen", "reopened");

    private final String label;
    private final String kind;

    AuditAction(String label, String kind) {
        this.label = label;
        this.kind = kind;
    }

    public String label() {
        return label;
    }

    /**
     * CSS modifier used by the audit badges.
     */
    public String kind() {
        return kind;
    }
}

package cz.domaci.odmeny.ledger;

/**
 * Types of immutable money movements in the append-only ledger.
 */
public enum LedgerEntryType {

    TASK_REWARD("Odměna za práci"),
    BASE_ALLOWANCE("Základní odměna"),
    ADJUSTMENT("Úprava"),
    PAYOUT("Výplata"),
    REVERSAL("Storno");

    private final String label;

    LedgerEntryType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

package cz.domaci.odmeny.web;

import java.util.Optional;

/**
 * Small, fixed set of post-redirect-get messages. Query parameters only carry
 * a code, never user text, so a crafted link cannot inject content into a page.
 */
public record Flash(String text, String kind) {

    public static final String SUCCESS = "success";
    public static final String WARNING = "warning";
    public static final String ERROR = "error";

    public static Optional<Flash> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return switch (code) {
            case "logged" -> Optional.of(new Flash("Práce byla zapsána. Děkujeme!", SUCCESS));
            case "approved" -> Optional.of(new Flash("Práce byla schválena a odměna připsána.", SUCCESS));
            case "rejected" -> Optional.of(new Flash("Práce byla zamítnuta.", SUCCESS));
            case "task" -> Optional.of(new Flash("Tato práce už není dostupná.", ERROR));
            case "invalid" -> Optional.of(new Flash("Zadané údaje nejsou platné.", ERROR));
            case "conflict" -> Optional.of(new Flash("Tato práce už byla vyřízena.", WARNING));
            case "missing" -> Optional.of(new Flash("Práce nebyla nalezena.", ERROR));
            case "profile" -> Optional.of(new Flash("Váš účet zatím není propojen s členem domácnosti.", ERROR));
            case "storage" -> Optional.of(new Flash("Databáze není dostupná. Zkuste to prosím později.", ERROR));
            default -> Optional.empty();
        };
    }
}

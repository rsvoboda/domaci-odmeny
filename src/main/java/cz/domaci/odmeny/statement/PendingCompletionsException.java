package cz.domaci.odmeny.statement;

/**
 * A month cannot be closed while it still has completions waiting for review.
 */
public class PendingCompletionsException extends RuntimeException {

    public PendingCompletionsException(String message) {
        super(message);
    }
}

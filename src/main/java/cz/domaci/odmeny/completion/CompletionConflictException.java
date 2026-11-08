package cz.domaci.odmeny.completion;

/**
 * The completion was already approved or rejected, or another transaction
 * changed it concurrently.
 */
public class CompletionConflictException extends RuntimeException {

    public CompletionConflictException(String message) {
        super(message);
    }

    public CompletionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

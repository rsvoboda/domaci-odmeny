package cz.domaci.odmeny.completion;

/**
 * The requested completion does not exist or is no longer in the approval
 * queue.
 */
public class CompletionNotFoundException extends RuntimeException {

    public CompletionNotFoundException(String completionId) {
        super("Completion not found: " + completionId);
    }
}

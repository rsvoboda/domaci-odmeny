package cz.domaci.odmeny.completion;

/**
 * The submitted completion data is invalid, for example a missing rejection
 * reason.
 */
public class InvalidCompletionException extends RuntimeException {

    public InvalidCompletionException(String message) {
        super(message);
    }
}

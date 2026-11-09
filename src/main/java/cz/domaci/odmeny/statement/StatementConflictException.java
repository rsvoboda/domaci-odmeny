package cz.domaci.odmeny.statement;

/**
 * The statement changed concurrently or is already in the requested state.
 */
public class StatementConflictException extends RuntimeException {

    public StatementConflictException(String message) {
        super(message);
    }

    public StatementConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

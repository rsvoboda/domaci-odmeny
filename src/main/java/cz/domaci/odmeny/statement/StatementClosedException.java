package cz.domaci.odmeny.statement;

/**
 * A correction or another money change was attempted in a closed month.
 */
public class StatementClosedException extends RuntimeException {

    public StatementClosedException(String message) {
        super(message);
    }
}

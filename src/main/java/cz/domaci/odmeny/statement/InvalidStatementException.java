package cz.domaci.odmeny.statement;

/**
 * The requested statement operation is not valid, for example a future period
 * or a payout before the month is closed.
 */
public class InvalidStatementException extends RuntimeException {

    public InvalidStatementException(String message) {
        super(message);
    }
}

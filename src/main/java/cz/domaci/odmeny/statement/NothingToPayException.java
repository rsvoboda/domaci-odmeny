package cz.domaci.odmeny.statement;

/**
 * The statement's balance is zero or negative, so there is no payout to record.
 */
public class NothingToPayException extends RuntimeException {

    public NothingToPayException(String message) {
        super(message);
    }
}

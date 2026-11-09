package cz.domaci.odmeny.household;

/**
 * Thrown when an operation would change money in a period that is already
 * closed. The parent must reopen the month explicitly first.
 */
public class MonthClosedException extends RuntimeException {

    public MonthClosedException(String message) {
        super(message);
    }
}

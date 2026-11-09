package cz.domaci.odmeny.household;

import cz.domaci.odmeny.shared.Period;

/**
 * Answers whether a member's accounting period is still open.
 *
 * <p>This small interface lives in the household package so that the
 * completion workflow can depend on the period rule without depending on the
 * statement implementation. The statement package provides the implementation.
 */
public interface PeriodGate {

    /**
     * An open period has no stored statement, or its statement was explicitly
     * reopened.
     */
    boolean isOpen(String memberId, Period period);

    /**
     * The first open period at or after {@code start}. Used to move late work
     * into the next open month when the current month is already closed.
     */
    Period nextOpen(String memberId, Period start);
}

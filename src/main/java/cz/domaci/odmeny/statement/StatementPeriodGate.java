package cz.domaci.odmeny.statement;

import cz.domaci.odmeny.household.PeriodGate;
import cz.domaci.odmeny.shared.Period;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The statement-backed implementation of the period rule.
 */
@ApplicationScoped
public class StatementPeriodGate implements PeriodGate {

    private static final int MAX_MONTHS_AHEAD = 240;

    private final StatementRepository statements;

    @Inject
    public StatementPeriodGate(StatementRepository statements) {
        this.statements = statements;
    }

    @Override
    public boolean isOpen(String memberId, Period period) {
        return statements.find(memberId, period)
                .map(Statement::isOpen)
                .orElse(true);
    }

    @Override
    public Period nextOpen(String memberId, Period start) {
        Period period = start;
        for (int i = 0; i < MAX_MONTHS_AHEAD; i++) {
            if (isOpen(memberId, period)) {
                return period;
            }
            period = period.next();
        }
        throw new IllegalStateException(
                "No open period found within " + MAX_MONTHS_AHEAD + " months for member " + memberId);
    }
}

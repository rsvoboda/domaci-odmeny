package cz.domaci.odmeny.ledger;

import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Calculates the current month's earnings from the base amount and the ledger.
 *
 * <p>Statements (Slice 3) will snapshot these values when a month is closed.
 */
@ApplicationScoped
public class EarningsService {

    private final LedgerRepository ledger;
    private final HouseholdService household;

    @Inject
    public EarningsService(LedgerRepository ledger, HouseholdService household) {
        this.ledger = ledger;
        this.household = household;
    }

    public EarningsSummary currentMonth(Member member) {
        Period period = household.currentPeriod();
        List<LedgerEntry> entries = ledger.listEntries(member.id(), period);

        Money bonus = Money.ZERO;
        for (LedgerEntry entry : entries) {
            // Payouts reduce the balance due, not the amount earned, so the
            // child's home total stays the month's earnings.
            if (entry.type() != LedgerEntryType.BASE_ALLOWANCE
                    && entry.type() != LedgerEntryType.PAYOUT) {
                bonus = bonus.plus(entry.amount());
            }
        }

        return new EarningsSummary(member.baseAllowance(), bonus);
    }

    public record EarningsSummary(Money base, Money bonus) {

        public Money total() {
            return base.plus(bonus);
        }
    }
}

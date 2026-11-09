package cz.domaci.odmeny.audit;

import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.shared.Period;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Creates audit events and reads the trail.
 *
 * <p>Services create an event and pass it to the repository operation, so the
 * event and the state change are committed together.
 */
@ApplicationScoped
public class AuditService {

    private final AuditRepository repository;
    private final HouseholdService household;

    @Inject
    public AuditService(AuditRepository repository, HouseholdService household) {
        this.repository = repository;
        this.household = household;
    }

    public AuditEvent create(Member actor, String memberId, String memberName, Period period,
            AuditAction action, String details) {
        Instant now = household.now();
        return new AuditEvent(
                UUID.randomUUID().toString(),
                household.id(),
                period,
                now,
                actor.id(),
                actor.displayName(),
                action,
                memberId,
                memberName,
                details);
    }

    /**
     * Events for a month, newest first.
     */
    public List<AuditEvent> list(Period period) {
        return repository.listByPeriod(period).reversed();
    }

    /**
     * Every event from {@code earliest} to the current period, oldest first.
     */
    public List<AuditEvent> listAll(Period earliest) {
        return repository.listBetween(earliest, household.currentPeriod()).stream()
                .sorted(Comparator.comparing(AuditEvent::occurredAt))
                .toList();
    }
}

package cz.domaci.odmeny.household;

import cz.domaci.odmeny.config.AppConfig;
import cz.domaci.odmeny.shared.Period;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Resolves the current household and its accounting period.
 *
 * <p>If the household record has not been provisioned yet, the configured
 * defaults are used. This keeps the application usable during the first
 * deployment without a write-on-read side effect.
 */
@ApplicationScoped
public class HouseholdService {

    private final HouseholdRepository repository;
    private final AppConfig config;
    private final Clock clock;

    @Inject
    public HouseholdService(HouseholdRepository repository, AppConfig config) {
        this(repository, config, Clock.systemUTC());
    }

    HouseholdService(HouseholdRepository repository, AppConfig config, Clock clock) {
        this.repository = repository;
        this.config = config;
        this.clock = clock;
    }

    public Household current() {
        Optional<Household> stored = repository.findById(config.householdId());
        return stored.orElseGet(this::configuredDefault);
    }

    public String id() {
        return config.householdId();
    }

    public String name() {
        return current().name();
    }

    public ZoneId zone() {
        return current().zone();
    }

    public Period currentPeriod() {
        return Period.from(clock.instant(), zone());
    }

    public Instant now() {
        return clock.instant();
    }

    private Household configuredDefault() {
        return new Household(
                config.householdId(),
                config.householdName(),
                config.currency(),
                config.timezone());
    }
}

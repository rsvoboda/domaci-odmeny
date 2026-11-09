package cz.domaci.odmeny.web;

import cz.domaci.odmeny.audit.AuditService;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.shared.Period;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parent-only audit trail. Every approval, correction, close, payout and reopen
 * is listed with the actor, the affected child and the reason or amount.
 */
@Path("/audit")
@ApplicationScoped
@RolesAllowed("parents")
public class AuditResource {

    private static final Logger LOG = Logger.getLogger(AuditResource.class);
    private static final int MONTHS_SHOWN = 12;

    private final PageSupport pages;
    private final AuditService audits;
    private final HouseholdService household;

    @Inject
    @Location("audit.html")
    Template auditPage;

    @Inject
    public AuditResource(PageSupport pages, AuditService audits, HouseholdService household) {
        this.pages = pages;
        this.audits = audits;
        this.household = household;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@QueryParam("period") String periodText,
            @QueryParam("flash") String flash) {
        Map<String, Object> data = pages.base(flash);
        ZoneId zone = household.zone();
        Period current = household.currentPeriod();
        Period period = parsePeriod(periodText, current);

        data.put("periodValue", period.value());
        data.put("periodLabel", Formats.period(period, zone));
        data.put("months", months(current, period, zone));

        try {
            data.put("events", audits.list(period).stream()
                    .map(event -> Views.audit(event, zone))
                    .toList());
            data.put("storageUnavailable", false);
        } catch (SdkException e) {
            LOG.warnf("Could not read the audit trail: %s", e.getMessage());
            data.put("events", List.of());
            data.put("storageUnavailable", true);
        }

        return auditPage.data(data);
    }

    private static Period parsePeriod(String text, Period fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Period.parse(text);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException();
        }
    }

    private static List<Views.PeriodLinkView> months(Period current, Period selected, ZoneId zone) {
        List<Views.PeriodLinkView> months = new ArrayList<>();
        Period period = current;
        for (int i = 0; i < MONTHS_SHOWN; i++) {
            months.add(Views.periodLink(period, zone, period.equals(selected)));
            period = period.previous();
        }
        return months;
    }
}

package cz.domaci.odmeny.web;

import cz.domaci.odmeny.completion.CompletionService;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.shared.Period;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A child's own completion history. Children never see a sibling's activity.
 */
@Path("/history")
@ApplicationScoped
@RolesAllowed("children")
public class HistoryResource {

    private static final Logger LOG = Logger.getLogger(HistoryResource.class);

    private final CurrentUser currentUser;
    private final PageSupport pages;
    private final CompletionService completions;
    private final HouseholdService household;

    @Inject
    @Location("history.html")
    Template history;

    @Inject
    public HistoryResource(CurrentUser currentUser, PageSupport pages, CompletionService completions,
            HouseholdService household) {
        this.currentUser = currentUser;
        this.pages = pages;
        this.completions = completions;
        this.household = household;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@QueryParam("flash") String flash) {
        Map<String, Object> data = pages.base(flash);
        try {
            Optional<Member> member = currentUser.member();
            if (member.isEmpty()) {
                data.put("groups", List.of());
                data.put("storageUnavailable", false);
                return history.data(data);
            }

            ZoneId zone = household.zone();
            Period current = household.currentPeriod();
            Period previous = current.previous();

            data.put("displayName", member.get().displayName());
            data.put("groups", List.of(
                    Views.period(current, views(member.get(), current, zone), zone),
                    Views.period(previous, views(member.get(), previous, zone), zone)));
            data.put("storageUnavailable", false);
        } catch (SdkException e) {
            LOG.warnf("Could not read the completion history: %s", e.getMessage());
            data.put("groups", List.of());
            data.put("storageUnavailable", true);
        }
        return history.data(data);
    }

    private List<Views.CompletionView> views(Member member, Period period, ZoneId zone) {
        return completions.history(member, period).stream()
                .map(completion -> Views.completion(completion, zone))
                .toList();
    }
}

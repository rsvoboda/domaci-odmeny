package cz.domaci.odmeny.web;

import cz.domaci.odmeny.completion.CompletionService;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.ledger.EarningsService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.task.TaskService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
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
 * Role-aware home screen: children see the chore buttons and their current
 * total; parents see the approval queue and each child's total.
 */
@Path("/")
@ApplicationScoped
public class HomeResource {

    private static final Logger LOG = Logger.getLogger(HomeResource.class);

    private final CurrentUser currentUser;
    private final PageSupport pages;
    private final MemberService members;
    private final TaskService tasks;
    private final CompletionService completions;
    private final EarningsService earnings;
    private final HouseholdService household;

    @Inject
    @Location("child-home.html")
    Template childHome;

    @Inject
    @Location("parent-home.html")
    Template parentHome;

    @Inject
    @Location("profile-missing.html")
    Template profileMissing;

    @Inject
    public HomeResource(CurrentUser currentUser, PageSupport pages, MemberService members,
            TaskService tasks, CompletionService completions, EarningsService earnings,
            HouseholdService household) {
        this.currentUser = currentUser;
        this.pages = pages;
        this.members = members;
        this.tasks = tasks;
        this.completions = completions;
        this.earnings = earnings;
        this.household = household;
    }

    @GET
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@QueryParam("flash") String flash) {
        Map<String, Object> data = pages.base(flash);
        boolean parent = currentUser.isParent();

        try {
            Optional<Member> member = currentUser.member();
            if (member.isEmpty()) {
                data.put("storageUnavailable", false);
                return profileMissing.data(data);
            }

            data.put("member", member.get());
            data.put("displayName", member.get().displayName());
            data.put("storageUnavailable", false);
            if (parent) {
                addParentData(data, member.get());
                return parentHome.data(data);
            }
            addChildData(data, member.get());
            return childHome.data(data);
        } catch (SdkException e) {
            LOG.warnf("Could not read the home screen: %s", e.getMessage());
            data.put("storageUnavailable", true);
            if (parent) {
                data.put("pending", List.of());
                data.put("pendingCount", 0);
                data.put("children", List.of());
                return parentHome.data(data);
            }
            data.put("tasks", List.of());
            data.put("history", List.of());
            data.put("periodLabel", "");
            data.put("baseText", "");
            data.put("bonusText", "");
            data.put("totalText", "");
            return childHome.data(data);
        }
    }

    private void addChildData(Map<String, Object> data, Member child) {
        ZoneId zone = household.zone();
        Period period = household.currentPeriod();

        data.put("periodLabel", Formats.period(period, zone));
        data.put("tasks", tasks.listActive().stream().map(Views::task).toList());
        data.put("history", completions.currentMonth(child).stream()
                .limit(5)
                .map(completion -> Views.completion(completion, zone))
                .toList());

        EarningsService.EarningsSummary summary = earnings.currentMonth(child);
        data.put("baseText", Formats.money(summary.base()));
        data.put("bonusText", Formats.money(summary.bonus()));
        data.put("totalText", Formats.money(summary.total()));
    }

    private void addParentData(Map<String, Object> data, Member parent) {
        ZoneId zone = household.zone();

        List<Views.PendingView> pending = completions.pending().stream()
                .map(item -> Views.pending(item, zone))
                .toList();
        data.put("pending", pending);
        data.put("pendingCount", pending.size());

        List<Views.MemberTotalView> children = members.listHouseholdMembers().stream()
                .filter(member -> !member.isParent() && member.active())
                .map(member -> Views.total(member, earnings.currentMonth(member)))
                .toList();
        data.put("children", children);
        data.put("parentName", parent.displayName());
    }
}

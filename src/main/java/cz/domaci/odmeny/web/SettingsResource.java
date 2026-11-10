package cz.domaci.odmeny.web;

import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.settings.InvalidSettingsException;
import cz.domaci.odmeny.settings.SettingsService;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.task.TaskService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Parent-only settings: household name, member names and allowances, and the
 * chore catalog. Every save redirects back with a fixed flash code.
 */
@Path("/settings")
@ApplicationScoped
@RolesAllowed("parents")
public class SettingsResource {

    private static final Logger LOG = Logger.getLogger(SettingsResource.class);

    private final CurrentUser currentUser;
    private final PageSupport pages;
    private final SettingsService settings;
    private final MemberService members;
    private final TaskService tasks;
    private final HouseholdService household;

    @Inject
    @Location("settings.html")
    Template settingsPage;

    @Inject
    public SettingsResource(CurrentUser currentUser, PageSupport pages, SettingsService settings,
            MemberService members, TaskService tasks, HouseholdService household) {
        this.currentUser = currentUser;
        this.pages = pages;
        this.settings = settings;
        this.members = members;
        this.tasks = tasks;
        this.household = household;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@QueryParam("flash") String flash) {
        Map<String, Object> data = pages.base(flash);
        try {
            List<Member> householdMembers = members.listHouseholdMembers().stream()
                    .sorted(Comparator.comparing(Member::isParent).reversed()
                            .thenComparing(Member::displayName))
                    .toList();
            data.put("members", householdMembers.stream().map(Views::memberSettings).toList());
            data.put("tasks", tasks.listAll().stream().map(Views::taskSettings).toList());
            data.put("storageUnavailable", false);
        } catch (SdkException e) {
            LOG.warnf("Could not read settings: %s", e.getMessage());
            data.put("members", List.of());
            data.put("tasks", List.of());
            data.put("storageUnavailable", true);
        }
        return settingsPage.data(data);
    }

    @POST
    @Path("/household")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response renameHousehold(@FormParam("name") String name) {
        try {
            settings.renameHousehold(actor(), name);
            return redirect("household-saved");
        } catch (InvalidSettingsException e) {
            return redirect("settings-invalid");
        } catch (IllegalStateException e) {
            LOG.warnf("Could not rename the household: %s", e.getMessage());
            return redirect("settings-conflict");
        } catch (SdkException e) {
            LOG.warnf("Could not rename the household: %s", e.getMessage());
            return redirect("storage");
        }
    }

    @POST
    @Path("/members/{memberId}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateMember(@PathParam("memberId") String memberId,
            @FormParam("displayName") String displayName,
            @FormParam("baseAllowance") String baseAllowance,
            @FormParam("active") String active) {
        try {
            Money base = baseAllowance == null || baseAllowance.isBlank()
                    ? null
                    : MoneyParser.parse(baseAllowance);
            settings.updateMember(actor(), memberId, displayName, base, checked(active));
            return redirect("member-saved");
        } catch (InvalidSettingsException | IllegalArgumentException e) {
            return redirect("settings-invalid");
        } catch (IllegalStateException e) {
            LOG.warnf("Could not update the member: %s", e.getMessage());
            return redirect("settings-conflict");
        } catch (SdkException e) {
            LOG.warnf("Could not update the member: %s", e.getMessage());
            return redirect("storage");
        }
    }

    @POST
    @Path("/tasks")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response saveTask(@FormParam("taskId") String taskId,
            @FormParam("name") String name,
            @FormParam("description") String description,
            @FormParam("reward") String reward,
            @FormParam("requiresApproval") String requiresApproval,
            @FormParam("active") String active) {
        boolean creating = taskId == null || taskId.isBlank();
        try {
            settings.saveTask(actor(), taskId, name, description, MoneyParser.parse(reward),
                    checked(requiresApproval), checked(active));
            return redirect(creating ? "task-added" : "task-saved");
        } catch (InvalidSettingsException | IllegalArgumentException e) {
            return redirect("settings-invalid");
        } catch (IllegalStateException e) {
            LOG.warnf("Could not save the task: %s", e.getMessage());
            return redirect("settings-conflict");
        } catch (SdkException e) {
            LOG.warnf("Could not save the task: %s", e.getMessage());
            return redirect("storage");
        }
    }

    private Member actor() {
        return currentUser.member().orElseThrow(ForbiddenException::new);
    }

    private static boolean checked(String value) {
        return "on".equals(value) || "true".equals(value);
    }

    private static Response redirect(String flash) {
        return Response.seeOther(URI.create("/settings?flash=" + flash)).build();
    }
}

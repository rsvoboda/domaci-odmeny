package cz.domaci.odmeny.web;

import cz.domaci.odmeny.completion.CompletionConflictException;
import cz.domaci.odmeny.completion.CompletionNotFoundException;
import cz.domaci.odmeny.completion.CompletionService;
import cz.domaci.odmeny.completion.InvalidCompletionException;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.household.MonthClosedException;
import cz.domaci.odmeny.member.Member;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parent-only approval queue.
 */
@Path("/approvals")
@ApplicationScoped
@RolesAllowed("parents")
public class ApprovalResource {

    private static final Logger LOG = Logger.getLogger(ApprovalResource.class);

    private final CurrentUser currentUser;
    private final PageSupport pages;
    private final CompletionService completions;
    private final HouseholdService household;

    @Inject
    @Location("approvals.html")
    Template approvals;

    @Inject
    public ApprovalResource(CurrentUser currentUser, PageSupport pages, CompletionService completions,
            HouseholdService household) {
        this.currentUser = currentUser;
        this.pages = pages;
        this.completions = completions;
        this.household = household;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance queue(@QueryParam("flash") String flash) {
        Map<String, Object> data = pages.base(flash);
        try {
            currentUser.member().ifPresent(member -> data.put("displayName", member.displayName()));
            List<Views.PendingView> pending = completions.pending().stream()
                    .map(item -> Views.pending(item, household.zone()))
                    .toList();
            data.put("pending", pending);
            data.put("pendingCount", pending.size());
            data.put("storageUnavailable", false);
        } catch (SdkException e) {
            LOG.warnf("Could not read the approval queue: %s", e.getMessage());
            data.put("pending", List.of());
            data.put("pendingCount", 0);
            data.put("storageUnavailable", true);
        }
        return approvals.data(data);
    }

    @POST
    @Path("/{id}/approve")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response approve(@PathParam("id") String completionId) {
        return review(completionId, null, true);
    }

    @POST
    @Path("/{id}/reject")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response reject(@PathParam("id") String completionId, @FormParam("reason") String reason) {
        return review(completionId, reason, false);
    }

    private Response review(String completionId, String reason, boolean approve) {
        try {
            Optional<Member> parent = currentUser.member();
            if (parent.isEmpty()) {
                return redirect("/approvals?flash=profile");
            }

            if (approve) {
                completions.approve(parent.get(), completionId);
                return redirect("/approvals?flash=approved");
            }
            completions.reject(parent.get(), completionId, reason);
            return redirect("/approvals?flash=rejected");
        } catch (CompletionNotFoundException e) {
            return redirect("/approvals?flash=missing");
        } catch (CompletionConflictException e) {
            return redirect("/approvals?flash=conflict");
        } catch (MonthClosedException e) {
            return redirect("/approvals?flash=closed-month");
        } catch (InvalidCompletionException e) {
            return redirect("/approvals?flash=invalid");
        } catch (SdkException e) {
            LOG.warnf("Could not review the completion: %s", e.getMessage());
            return redirect("/approvals?flash=storage");
        }
    }

    private static Response redirect(String location) {
        return Response.seeOther(URI.create(location)).build();
    }
}

package cz.domaci.odmeny.web;

import cz.domaci.odmeny.completion.CompletionService;
import cz.domaci.odmeny.completion.InvalidCompletionException;
import cz.domaci.odmeny.completion.TaskNotAvailableException;
import cz.domaci.odmeny.member.Member;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;

import java.net.URI;
import java.util.Optional;

/**
 * Child-only endpoint for logging a completed chore.
 */
@Path("/completions")
@ApplicationScoped
public class CompletionResource {

    private static final Logger LOG = Logger.getLogger(CompletionResource.class);

    private final CurrentUser currentUser;
    private final CompletionService completions;

    @Inject
    public CompletionResource(CurrentUser currentUser, CompletionService completions) {
        this.currentUser = currentUser;
        this.completions = completions;
    }

    @POST
    @RolesAllowed("children")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response log(@FormParam("taskId") String taskId, @FormParam("note") String note) {
        try {
            Optional<Member> child = currentUser.member();
            if (child.isEmpty()) {
                return redirect("/?flash=profile");
            }
            completions.logChore(child.get(), taskId, note);
            return redirect("/?flash=logged");
        } catch (TaskNotAvailableException e) {
            return redirect("/?flash=task");
        } catch (InvalidCompletionException e) {
            return redirect("/?flash=invalid");
        } catch (SdkException e) {
            LOG.warnf("Could not store the completion: %s", e.getMessage());
            return redirect("/?flash=storage");
        }
    }

    private static Response redirect(String location) {
        return Response.seeOther(URI.create(location)).build();
    }
}

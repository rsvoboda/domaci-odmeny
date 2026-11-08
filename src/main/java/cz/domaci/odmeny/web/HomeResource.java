package cz.domaci.odmeny.web;

import cz.domaci.odmeny.config.AppConfig;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberRole;
import cz.domaci.odmeny.member.MemberService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one protected page of Slice 1. Later slices replace this dashboard with
 * the child and parent home screens.
 */
@Path("/")
@ApplicationScoped
public class HomeResource {

    private static final Logger LOG = Logger.getLogger(HomeResource.class);

    private final SecurityIdentity identity;
    private final MemberService memberService;
    private final AppConfig config;
    private final Template home;

    @Inject
    public HomeResource(SecurityIdentity identity, MemberService memberService, AppConfig config, Template home) {
        this.identity = identity;
        this.memberService = memberService;
        this.config = config;
        this.home = home;
    }

    @GET
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index() {
        Optional<Member> member = Optional.empty();
        boolean storageUnavailable = false;

        try {
            member = memberService.findByCognitoSub(subject());
        } catch (SdkException e) {
            storageUnavailable = true;
            LOG.warnf("Could not read the member record: %s", e.getMessage());
        }

        Member current = member.orElse(null);
        boolean parent = identity.hasRole(MemberRole.PARENT.groupName());

        Map<String, Object> data = new HashMap<>();
        data.put("appName", config.name());
        data.put("householdName", config.householdName());
        data.put("displayName", displayName());
        data.put("roleLabel", parent ? MemberRole.PARENT.label() : MemberRole.CHILD.label());
        data.put("isParent", parent);
        data.put("member", current);
        data.put("storageUnavailable", storageUnavailable);
        data.put("storageAvailable", !storageUnavailable);
        data.put("baseAllowanceText", current == null ? "" : MoneyFormat.format(current.baseAllowance()));

        return home.data(data);
    }

    private String subject() {
        String sub = identity.getAttribute("sub");
        return sub != null ? sub : identity.getPrincipal().getName();
    }

    private String displayName() {
        String name = identity.getAttribute("name");
        if (name == null) {
            name = identity.getAttribute("cognito:username");
        }
        if (name == null) {
            name = identity.getAttribute("display_name");
        }
        return name != null ? name : identity.getPrincipal().getName();
    }
}

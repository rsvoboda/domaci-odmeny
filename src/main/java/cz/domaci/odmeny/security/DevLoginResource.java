package cz.domaci.odmeny.security;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.Map;

/**
 * Development-only login chooser. Sets a cookie consumed by
 * {@link DevAuthMechanism}.
 */
@Path("/dev-login")
@ApplicationScoped
@IfBuildProperty(name = "app.dev-auth.enabled", stringValue = "true")
public class DevLoginResource {

    @Inject
    @Location("dev-login.html")
    Template devLogin;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page() {
        var users = DevAuthMechanism.users().stream()
                .map(user -> Map.of(
                        "key", user.key(),
                        "displayName", user.displayName(),
                        "roleLabel", user.role().label()))
                .toList();
        return devLogin.data("users", users);
    }

    @GET
    @Path("/{key}")
    public Response login(@PathParam("key") String key) {
        var user = DevAuthMechanism.user(key);
        if (user.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.seeOther(URI.create("/"))
                .cookie(new NewCookie.Builder(DevAuthMechanism.COOKIE_NAME)
                        .value(user.get().key())
                        .path("/")
                        .httpOnly(true)
                        .sameSite(NewCookie.SameSite.LAX)
                        .build())
                .build();
    }
}

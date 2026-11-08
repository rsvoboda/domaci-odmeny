package cz.domaci.odmeny.security;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * Development-only logout: clears the dev login cookie. In production the OIDC
 * extension handles {@code /logout} and ends the Cognito session.
 */
@Path("/logout")
@ApplicationScoped
@IfBuildProperty(name = "app.dev-auth.enabled", stringValue = "true")
public class DevLogoutResource {

    @GET
    public Response logout() {
        return Response.seeOther(URI.create("/dev-login"))
                .cookie(new NewCookie.Builder(DevAuthMechanism.COOKIE_NAME)
                        .value("")
                        .path("/")
                        .maxAge(0)
                        .httpOnly(true)
                        .build())
                .build();
    }
}

package cz.domaci.odmeny.security;

import cz.domaci.odmeny.member.MemberRole;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Development-only authentication. Replaces Cognito locally so the app can run
 * without an identity provider or a container runtime.
 *
 * <p>Enabled only when {@code app.dev-auth.enabled=true}, which is set in the
 * {@code dev} profile. The packaged production application does not contain
 * this bean.
 */
@ApplicationScoped
@IfBuildProperty(name = "app.dev-auth.enabled", stringValue = "true")
public class DevAuthMechanism implements HttpAuthenticationMechanism {

    public static final String COOKIE_NAME = "dev_user";

    private static final Map<String, DevUser> USERS;

    static {
        Map<String, DevUser> users = new LinkedHashMap<>();
        users.put("parent1", new DevUser("parent1", "dev-parent-1", "Rodič 1", MemberRole.PARENT));
        users.put("parent2", new DevUser("parent2", "dev-parent-2", "Rodič 2", MemberRole.PARENT));
        users.put("child1", new DevUser("child1", "dev-child-1", "Dítě 1", MemberRole.CHILD));
        users.put("child2", new DevUser("child2", "dev-child-2", "Dítě 2", MemberRole.CHILD));
        USERS = Collections.unmodifiableMap(users);
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context,
            IdentityProviderManager identityProviderManager) {
        var cookie = context.request().getCookie(COOKIE_NAME);
        if (cookie == null) {
            return Uni.createFrom().nullItem();
        }

        DevUser user = USERS.get(cookie.getValue());
        if (user == null) {
            return Uni.createFrom().nullItem();
        }

        Principal principal = user::sub;
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(principal)
                .addRole(user.role().groupName())
                .addAttribute("sub", user.sub())
                .addAttribute("display_name", user.displayName())
                .build();

        return Uni.createFrom().item(identity);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(302, HttpHeaders.LOCATION, "/dev-login"));
    }

    public static List<DevUser> users() {
        return List.copyOf(USERS.values());
    }

    public static Optional<DevUser> user(String key) {
        return Optional.ofNullable(USERS.get(key));
    }

    public record DevUser(String key, String sub, String displayName, MemberRole role) {
    }
}

package cz.domaci.odmeny.web;

import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberRole;
import cz.domaci.odmeny.member.MemberService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves the authenticated principal to a household member.
 */
@ApplicationScoped
public class CurrentUser {

    private final SecurityIdentity identity;
    private final MemberService members;

    @Inject
    public CurrentUser(SecurityIdentity identity, MemberService members) {
        this.identity = identity;
        this.members = members;
    }

    public Optional<Member> member() {
        return members.findByCognitoSub(subject());
    }

    public String subject() {
        String sub = identity.getAttribute("sub");
        return sub != null ? sub : identity.getPrincipal().getName();
    }

    public String displayName() {
        String name = identity.getAttribute("name");
        if (name == null) {
            name = identity.getAttribute("cognito:username");
        }
        if (name == null) {
            name = identity.getAttribute("display_name");
        }
        return name != null ? name : identity.getPrincipal().getName();
    }

    public boolean isParent() {
        return identity.hasRole(MemberRole.PARENT.groupName());
    }

    public String roleLabel() {
        return isParent() ? MemberRole.PARENT.label() : MemberRole.CHILD.label();
    }
}

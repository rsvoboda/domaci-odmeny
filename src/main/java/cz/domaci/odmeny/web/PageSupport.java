package cz.domaci.odmeny.web;

import cz.domaci.odmeny.config.AppConfig;
import cz.domaci.odmeny.household.HouseholdService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the data shared by every Qute page: application name, household,
 * current user and the post-redirect-get flash message.
 */
@ApplicationScoped
public class PageSupport {

    private final AppConfig config;
    private final CurrentUser currentUser;
    private final HouseholdService household;

    @Inject
    public PageSupport(AppConfig config, CurrentUser currentUser, HouseholdService household) {
        this.config = config;
        this.currentUser = currentUser;
        this.household = household;
    }

    public Map<String, Object> base(String flashCode) {
        Map<String, Object> data = new HashMap<>();
        data.put("appName", config.name());
        data.put("householdName", householdName());
        data.put("displayName", currentUser.displayName());
        data.put("isParent", currentUser.isParent());
        data.put("roleLabel", currentUser.roleLabel());

        Flash.fromCode(flashCode).ifPresentOrElse(flash -> {
            data.put("flashText", flash.text());
            data.put("flashKind", flash.kind());
        }, () -> {
            data.put("flashText", "");
            data.put("flashKind", "");
        });

        return data;
    }

    /**
     * The stored household name, or the configured default while the
     * household record has not been provisioned or DynamoDB is unavailable.
     */
    private String householdName() {
        try {
            return household.name();
        } catch (SdkException e) {
            return config.householdName();
        }
    }
}

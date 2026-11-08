package cz.domaci.odmeny.member;

import cz.domaci.odmeny.shared.Money;

import java.util.Objects;

/**
 * A parent or child in the household.
 *
 * @param cognitoSub Cognito subject claim used to link the login to this member
 * @param baseAllowance base monthly amount, snapshotted when a month is closed
 */
public record Member(
        String id,
        String householdId,
        String cognitoSub,
        String displayName,
        MemberRole role,
        Money baseAllowance,
        boolean active) {

    public Member {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(cognitoSub, "cognitoSub");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(baseAllowance, "baseAllowance");
        if (baseAllowance.isNegative()) {
            throw new IllegalArgumentException("baseAllowance must not be negative");
        }
    }

    public boolean isParent() {
        return role.isParent();
    }
}

package cz.domaci.odmeny.member;

/**
 * A household member role. The Cognito group name is the source of truth for
 * authorization.
 */
public enum MemberRole {

    PARENT("parents", "Rodič"),
    CHILD("children", "Dítě");

    private final String groupName;
    private final String label;

    MemberRole(String groupName, String label) {
        this.groupName = groupName;
        this.label = label;
    }

    public String groupName() {
        return groupName;
    }

    public String label() {
        return label;
    }

    public boolean isParent() {
        return this == PARENT;
    }

    public static MemberRole fromGroup(String groupName) {
        for (MemberRole role : values()) {
            if (role.groupName.equals(groupName)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown role group: " + groupName);
    }
}

package cz.domaci.odmeny.task;

import cz.domaci.odmeny.shared.Money;

import java.util.Objects;

/**
 * A chore definition from the shared household catalog.
 *
 * @param reward fixed reward, snapshotted on a completion
 * @param requiresApproval when false the completion is credited immediately
 * @param sortOrder stable display order on the child home screen
 */
public record Task(
        String id,
        String householdId,
        String name,
        String description,
        Money reward,
        boolean requiresApproval,
        boolean active,
        int sortOrder) {

    public Task {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(reward, "reward");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (reward.isNegative()) {
            throw new IllegalArgumentException("reward must not be negative");
        }
    }
}

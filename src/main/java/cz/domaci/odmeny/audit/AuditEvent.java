package cz.domaci.odmeny.audit;

import cz.domaci.odmeny.shared.Period;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable record of one state change.
 *
 * <p>Events are partitioned by the accounting period they affect
 * ({@code PK = AUDIT#<yyyy-mm>}) and are written in the same DynamoDB
 * transaction as the change itself, so the trail cannot silently diverge from
 * the data.
 *
 * @param memberId   the child affected by the change
 * @param memberName display name captured at the time of the change
 * @param details    short human-readable context (task, note, reason, amount)
 */
public record AuditEvent(
        String id,
        String householdId,
        Period period,
        Instant occurredAt,
        String actorId,
        String actorName,
        AuditAction action,
        String memberId,
        String memberName,
        String details) {

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(householdId, "householdId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(memberName, "memberName");
    }
}

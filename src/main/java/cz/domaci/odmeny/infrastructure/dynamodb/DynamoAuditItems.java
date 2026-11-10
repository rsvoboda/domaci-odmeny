package cz.domaci.odmeny.infrastructure.dynamodb;

import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.shared.Period;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds audit items and transaction entries.
 *
 * <p>Audit events live in the Activity table, but the state change they
 * describe can live in another table (Members, Tasks). DynamoDB transactions
 * work across tables, so this helper lets every repository include the audit
 * write in the same transaction.
 */
final class DynamoAuditItems {

    private static final String ENTITY_AUDIT = "AUDIT";
    private static final String AUDIT_PREFIX = "AUDIT#";

    private DynamoAuditItems() {
    }

    static Map<String, AttributeValue> item(AuditEvent event) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(periodPrefix(event.period())));
        item.put("SK", AttributeValue.fromS(sortKey(event)));
        item.put("entityType", AttributeValue.fromS(ENTITY_AUDIT));
        item.put("id", AttributeValue.fromS(event.id()));
        item.put("householdId", AttributeValue.fromS(event.householdId()));
        item.put("period", AttributeValue.fromS(event.period().value()));
        item.put("occurredAtEpochMilli", AttributeValue.fromN(Long.toString(event.occurredAt().toEpochMilli())));
        item.put("actorId", AttributeValue.fromS(event.actorId()));
        item.put("actorName", AttributeValue.fromS(event.actorName()));
        item.put("action", AttributeValue.fromS(event.action().name()));
        putString(item, "memberId", event.memberId());
        putString(item, "memberName", event.memberName());
        putString(item, "details", event.details());
        return item;
    }

    static TransactWriteItem put(String tableName, AuditEvent event) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(item(event))
                        .build())
                .build();
    }

    static String periodPrefix(Period period) {
        return AUDIT_PREFIX + period.value();
    }

    static String sortKeyPrefix() {
        return AUDIT_PREFIX;
    }

    static String sortKey(AuditEvent event) {
        return AUDIT_PREFIX + paddedMillis(event.occurredAt().toEpochMilli()) + "#" + event.id();
    }

    private static String paddedMillis(long epochMilli) {
        return String.format(Locale.ROOT, "%019d", epochMilli);
    }

    private static void putString(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, AttributeValue.fromS(value));
        }
    }
}

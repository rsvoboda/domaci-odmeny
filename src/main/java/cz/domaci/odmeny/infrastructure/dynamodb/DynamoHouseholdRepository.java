package cz.domaci.odmeny.infrastructure.dynamodb;

import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.config.AppConfig;
import cz.domaci.odmeny.household.Household;
import cz.domaci.odmeny.household.HouseholdRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB adapter for household settings.
 *
 * <p>ADR-0004 has no separate settings table, so the household record lives in
 * the Members table under {@code SK = HOUSEHOLD#<id>}. The member query filters
 * on the {@code MEMBER#} prefix and never returns it.
 */
@ApplicationScoped
public class DynamoHouseholdRepository implements HouseholdRepository {

    private final DynamoDbClient client;
    private final AppConfig config;

    @Inject
    public DynamoHouseholdRepository(DynamoDbClient client, AppConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public Optional<Household> findById(String id) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName())
                .key(key(id))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        Map<String, AttributeValue> item = response.item();
        return Optional.of(new Household(
                item.get("id").s(),
                item.get("name").s(),
                item.get("currency").s(),
                item.get("timezone").s()));
    }

    @Override
    public void put(Household household) {
        client.putItem(PutItemRequest.builder()
                .tableName(tableName())
                .item(householdItem(household))
                .build());
    }

    @Override
    public void update(Household household, AuditEvent audit) {
        Put householdPut = Put.builder()
                .tableName(tableName())
                .item(householdItem(household))
                .conditionExpression("attribute_exists(PK)")
                .build();

        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder().put(householdPut).build(),
                            DynamoAuditItems.put(activityTable(), audit))
                    .build());
        } catch (TransactionCanceledException e) {
            throw new IllegalStateException("Household no longer exists: " + household.id(), e);
        }
    }

    private static Map<String, AttributeValue> householdItem(Household household) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(householdPk(household.id())));
        item.put("SK", AttributeValue.fromS(householdSk(household.id())));
        item.put("entityType", AttributeValue.fromS("HOUSEHOLD"));
        item.put("id", AttributeValue.fromS(household.id()));
        item.put("name", AttributeValue.fromS(household.name()));
        item.put("currency", AttributeValue.fromS(household.currency()));
        item.put("timezone", AttributeValue.fromS(household.timezone()));
        return item;
    }

    private String tableName() {
        return config.dynamodb().tables().members();
    }

    private String activityTable() {
        return config.dynamodb().tables().activity();
    }

    private static Map<String, AttributeValue> key(String id) {
        return Map.of(
                "PK", AttributeValue.fromS(householdPk(id)),
                "SK", AttributeValue.fromS(householdSk(id)));
    }

    private static String householdPk(String id) {
        return "HOUSEHOLD#" + id;
    }

    private static String householdSk(String id) {
        return "HOUSEHOLD#" + id;
    }
}

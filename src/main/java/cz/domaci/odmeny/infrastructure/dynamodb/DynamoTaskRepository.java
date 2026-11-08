package cz.domaci.odmeny.infrastructure.dynamodb;

import cz.domaci.odmeny.config.AppConfig;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.task.Task;
import cz.domaci.odmeny.task.TaskRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB adapter for the chore catalog.
 *
 * <p>Key layout (ADR-0004): {@code PK = HOUSEHOLD#<id>},
 * {@code SK = TASK#<id>}.
 */
@ApplicationScoped
public class DynamoTaskRepository implements TaskRepository {

    private final DynamoDbClient client;
    private final AppConfig config;

    @Inject
    public DynamoTaskRepository(DynamoDbClient client, AppConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public List<Task> listTasks(String householdId) {
        List<Task> tasks = new ArrayList<>();
        Map<String, AttributeValue> lastKey = null;

        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(tableName())
                    .keyConditionExpression("PK = :pk")
                    .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(householdPk(householdId))));

            if (lastKey != null && !lastKey.isEmpty()) {
                request.exclusiveStartKey(lastKey);
            }

            QueryResponse response = client.query(request.build());
            for (Map<String, AttributeValue> item : response.items()) {
                AttributeValue sk = item.get("SK");
                if (sk != null && sk.s() != null && sk.s().startsWith("TASK#")) {
                    tasks.add(toTask(item));
                }
            }
            lastKey = response.lastEvaluatedKey();
        } while (lastKey != null && !lastKey.isEmpty());

        return tasks;
    }

    @Override
    public Optional<Task> findById(String householdId, String taskId) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName())
                .key(Map.of(
                        "PK", AttributeValue.fromS(householdPk(householdId)),
                        "SK", AttributeValue.fromS("TASK#" + taskId)))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toTask(response.item()));
    }

    @Override
    public void put(Task task) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(householdPk(task.householdId())));
        item.put("SK", AttributeValue.fromS("TASK#" + task.id()));
        item.put("entityType", AttributeValue.fromS("TASK"));
        item.put("id", AttributeValue.fromS(task.id()));
        item.put("householdId", AttributeValue.fromS(task.householdId()));
        item.put("name", AttributeValue.fromS(task.name()));
        item.put("description", AttributeValue.fromS(task.description()));
        item.put("rewardMinor", AttributeValue.fromN(Long.toString(task.reward().minor())));
        item.put("requiresApproval", AttributeValue.fromBool(task.requiresApproval()));
        item.put("active", AttributeValue.fromBool(task.active()));
        item.put("sortOrder", AttributeValue.fromN(Integer.toString(task.sortOrder())));

        client.putItem(PutItemRequest.builder()
                .tableName(tableName())
                .item(item)
                .build());
    }

    private String tableName() {
        return config.dynamodb().tables().tasks();
    }

    private static String householdPk(String householdId) {
        return "HOUSEHOLD#" + householdId;
    }

    private static Task toTask(Map<String, AttributeValue> item) {
        return new Task(
                item.get("id").s(),
                item.get("householdId").s(),
                item.get("name").s(),
                item.get("description").s(),
                Money.minor(Long.parseLong(item.get("rewardMinor").n())),
                Boolean.TRUE.equals(item.get("requiresApproval").bool()),
                !item.containsKey("active") || Boolean.TRUE.equals(item.get("active").bool()),
                Integer.parseInt(item.get("sortOrder").n()));
    }
}

package cz.domaci.odmeny.infrastructure.dynamodb;

import cz.domaci.odmeny.config.AppConfig;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberRepository;
import cz.domaci.odmeny.member.MemberRole;
import cz.domaci.odmeny.shared.Money;
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
 * DynamoDB adapter for members.
 *
 * <p>Key layout (ADR-0004): {@code PK = HOUSEHOLD#<id>},
 * {@code SK = MEMBER#<id>}. With four members per household, looking a member
 * up by Cognito subject is a single-partition query plus an in-memory filter;
 * no GSI is needed.
 */
@ApplicationScoped
public class DynamoMemberRepository implements MemberRepository {

    private static final String ATTR_PK = "PK";
    private static final String ATTR_SK = "SK";

    private final DynamoDbClient client;
    private final AppConfig config;

    @Inject
    public DynamoMemberRepository(DynamoDbClient client, AppConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public List<Member> listByHousehold(String householdId) {
        List<Member> members = new ArrayList<>();
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
                AttributeValue sk = item.get(ATTR_SK);
                if (sk != null && sk.s() != null && sk.s().startsWith("MEMBER#")) {
                    members.add(toMember(item));
                }
            }
            lastKey = response.lastEvaluatedKey();
        } while (lastKey != null && !lastKey.isEmpty());

        return members;
    }

    @Override
    public Optional<Member> findMember(String householdId, String memberId) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName())
                .key(Map.of(
                        ATTR_PK, AttributeValue.fromS(householdPk(householdId)),
                        ATTR_SK, AttributeValue.fromS("MEMBER#" + memberId)))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toMember(response.item()));
    }

    @Override
    public Optional<Member> findByCognitoSub(String householdId, String cognitoSub) {
        return listByHousehold(householdId).stream()
                .filter(member -> member.cognitoSub().equals(cognitoSub))
                .findFirst();
    }

    @Override
    public void put(Member member) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_PK, AttributeValue.fromS(householdPk(member.householdId())));
        item.put(ATTR_SK, AttributeValue.fromS("MEMBER#" + member.id()));
        item.put("id", AttributeValue.fromS(member.id()));
        item.put("householdId", AttributeValue.fromS(member.householdId()));
        item.put("cognitoSub", AttributeValue.fromS(member.cognitoSub()));
        item.put("displayName", AttributeValue.fromS(member.displayName()));
        item.put("role", AttributeValue.fromS(member.role().name()));
        item.put("baseAllowanceMinor", AttributeValue.fromN(Long.toString(member.baseAllowance().minor())));
        item.put("active", AttributeValue.fromBool(member.active()));

        client.putItem(PutItemRequest.builder()
                .tableName(tableName())
                .item(item)
                .build());
    }

    private String tableName() {
        return config.dynamodb().tables().members();
    }

    private static String householdPk(String householdId) {
        return "HOUSEHOLD#" + householdId;
    }

    private static Member toMember(Map<String, AttributeValue> item) {
        return new Member(
                item.get("id").s(),
                item.get("householdId").s(),
                item.get("cognitoSub").s(),
                item.get("displayName").s(),
                MemberRole.valueOf(item.get("role").s()),
                Money.minor(Long.parseLong(item.get("baseAllowanceMinor").n())),
                !item.containsKey("active") || Boolean.TRUE.equals(item.get("active").bool()));
    }
}

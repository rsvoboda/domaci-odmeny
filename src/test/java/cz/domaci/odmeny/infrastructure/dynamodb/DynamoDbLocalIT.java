package cz.domaci.odmeny.infrastructure.dynamodb;

import cz.domaci.odmeny.audit.AuditAction;
import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.audit.AuditService;
import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionConflictException;
import cz.domaci.odmeny.completion.CompletionService;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.settings.SettingsService;
import cz.domaci.odmeny.statement.StatementService;
import cz.domaci.odmeny.task.Task;
import cz.domaci.odmeny.task.TaskService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real DynamoDB Local smoke test for the transactional audit writes.
 *
 * <p>This test is excluded from {@code mvn test} because it is named
 * {@code *IT} and only runs under the {@code integration} profile:
 * <pre>
 * podman compose up -d dynamodb
 * ./mvnw -Pintegration verify
 * </pre>
 *
 * <p>It uses its own tables ({@code *IT}) so a running development instance is
 * not disturbed.
 */
@QuarkusTest
@TestProfile(DynamoDbLocalIT.Profile.class)
class DynamoDbLocalIT {

    private static final String ACTIVITY_TABLE = "ActivityIT";
    private static final String PENDING_TABLE = "PendingIT";
    private static final String TASKS_TABLE = "TasksIT";

    @Inject
    DynamoDbClient client;

    @Inject
    CompletionService completions;

    @Inject
    StatementService statements;

    @Inject
    AuditService audits;

    @Inject
    MemberService members;

    @Inject
    HouseholdService household;

    @Inject
    SettingsService settings;

    @Inject
    TaskService tasks;

    @BeforeEach
    void clearActivity() {
        Assumptions.assumeTrue(reachable(), "DynamoDB Local is not running on localhost:8000");
        deleteAll(ACTIVITY_TABLE);
        deleteAll(PENDING_TABLE);
    }

    @Test
    void auditEventsAreCommittedWithTheStateChangeInRealDynamoDb() {
        Member child = member("dev-child-1");
        Member parent = member("dev-parent-1");
        Period period = household.currentPeriod();

        Completion pending = completions.logChore(child, "odpadky", "integrační test");
        assertEquals(1, completions.pending().size());

        completions.approve(parent, pending.id());
        assertTrue(completions.pending().isEmpty());

        statements.addAdjustment(parent, child, period, Money.czk(20), "Bonus");
        statements.close(parent, child, period);
        statements.markPaid(parent, child, period);
        statements.reopen(parent, child, period, "Chyba ve výpočtu");

        List<AuditAction> actions = audits.list(period).stream()
                .map(AuditEvent::action)
                .toList();

        assertEquals(List.of(
                AuditAction.MONTH_REOPENED,
                AuditAction.MONTH_PAID,
                AuditAction.MONTH_CLOSED,
                AuditAction.ADJUSTMENT_ADDED,
                AuditAction.CHORE_APPROVED,
                AuditAction.CHORE_LOGGED), actions);

        AuditEvent approved = audits.list(period).stream()
                .filter(event -> event.action() == AuditAction.CHORE_APPROVED)
                .findFirst()
                .orElseThrow();
        assertEquals(parent.id(), approved.actorId());
        assertEquals(child.id(), approved.memberId());
        assertTrue(approved.details().contains("Odpadky"));
    }

    @Test
    void failedApprovalDoesNotLeaveAnAuditEventBehind() {
        Member child = member("dev-child-1");
        Member parent = member("dev-parent-1");
        Period period = household.currentPeriod();

        Completion pending = completions.logChore(child, "odpadky", null);
        completions.approve(parent, pending.id());
        int before = audits.listAll(period).size();

        assertThrows(CompletionConflictException.class,
                () -> completions.approve(parent, pending.id()));

        assertEquals(before, audits.listAll(period).size());
        assertEquals(2, audits.list(period).size());
    }

    @Test
    void settingsChangesAndAuditsAreCommittedTogetherInRealDynamoDb() {
        Member parent = member("dev-parent-1");
        Member child = member("dev-child-1");
        Period period = household.currentPeriod();

        String originalHousehold = household.name();
        String originalChildName = child.displayName();
        Money originalBase = child.baseAllowance();
        boolean originalActive = child.active();
        Task created = null;

        try {
            settings.renameHousehold(parent, "IT domácnost");
            assertEquals("IT domácnost", household.name());

            settings.updateMember(parent, child.id(), "IT Dítě", Money.czk(60), true);
            Member updated = members.findById(child.id()).orElseThrow();
            assertEquals("IT Dítě", updated.displayName());
            assertEquals(Money.czk(60), updated.baseAllowance());

            created = settings.saveTask(parent, null, "IT práce", "Integrační test",
                    Money.czk(15), true, true);
            settings.saveTask(parent, created.id(), created.name(), created.description(),
                    Money.czk(20), false, true);

            String createdId = created.id();

            Task saved = tasks.listAll().stream()
                    .filter(task -> task.id().equals(createdId))
                    .findFirst()
                    .orElseThrow();
            assertEquals(Money.czk(20), saved.reward());

            List<AuditAction> actions = audits.list(period).stream()
                    .map(AuditEvent::action)
                    .toList();
            assertTrue(actions.containsAll(List.of(
                    AuditAction.HOUSEHOLD_RENAMED,
                    AuditAction.MEMBER_UPDATED,
                    AuditAction.TASK_CREATED,
                    AuditAction.TASK_UPDATED)));
        } finally {
            if (created != null) {
                client.deleteItem(DeleteItemRequest.builder()
                        .tableName(TASKS_TABLE)
                        .key(Map.of(
                                "PK", AttributeValue.fromS("HOUSEHOLD#it-household"),
                                "SK", AttributeValue.fromS("TASK#" + created.id())))
                        .build());
            }
            settings.updateMember(parent, child.id(), originalChildName, originalBase, originalActive);
            settings.renameHousehold(parent, originalHousehold);
        }
    }

    private boolean reachable() {
        try {
            client.describeTable(builder -> builder.tableName(ACTIVITY_TABLE));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void deleteAll(String table) {
        Map<String, AttributeValue> lastKey = null;
        do {
            ScanRequest.Builder request = ScanRequest.builder()
                    .tableName(table)
                    .projectionExpression("PK, SK");

            if (lastKey != null && !lastKey.isEmpty()) {
                request.exclusiveStartKey(lastKey);
            }

            ScanResponse response = client.scan(request.build());
            deleteBatch(table, response.items());
            lastKey = response.lastEvaluatedKey();
        } while (lastKey != null && !lastKey.isEmpty());
    }

    private void deleteBatch(String table, List<Map<String, AttributeValue>> items) {
        for (int start = 0; start < items.size(); start += 25) {
            List<Map<String, AttributeValue>> chunk = items.subList(start, Math.min(start + 25, items.size()));
            List<WriteRequest> writes = new ArrayList<>();
            for (Map<String, AttributeValue> item : chunk) {
                writes.add(WriteRequest.builder()
                        .deleteRequest(DeleteRequest.builder()
                                .key(Map.of(
                                        "PK", item.get("PK"),
                                        "SK", item.get("SK")))
                                .build())
                        .build());
            }
            client.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(table, writes))
                    .build());
        }
    }

    private Member member(String cognitoSub) {
        return members.findByCognitoSub(cognitoSub).orElseThrow();
    }

    /**
     * Uses the real DynamoDB adapters instead of the test-only in-memory store.
     */
    public static class Profile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("quarkus.arc.exclude-types",
                            "cz.domaci.odmeny.testsupport.InMemoryStore"),
                    Map.entry("quarkus.oidc.tenant-enabled", "false"),
                    Map.entry("app.dev-auth.enabled", "false"),
                    Map.entry("app.household-id", "it-household"),
                    Map.entry("app.dynamodb.endpoint-override", "http://localhost:8000"),
                    Map.entry("app.dynamodb.create-tables", "true"),
                    Map.entry("app.dynamodb.tables.members", "MembersIT"),
                    Map.entry("app.dynamodb.tables.tasks", "TasksIT"),
                    Map.entry("app.dynamodb.tables.activity", ACTIVITY_TABLE),
                    Map.entry("app.dynamodb.tables.pending", PENDING_TABLE),
                    Map.entry("app.seed.enabled", "true"),
                    Map.entry("app.seed.catalog", "true"));
        }
    }
}

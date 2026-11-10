package cz.domaci.odmeny.infrastructure.dynamodb;

import cz.domaci.odmeny.audit.AuditAction;
import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.audit.AuditRepository;
import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionConflictException;
import cz.domaci.odmeny.completion.CompletionRepository;
import cz.domaci.odmeny.completion.CompletionStatus;
import cz.domaci.odmeny.completion.PendingCompletion;
import cz.domaci.odmeny.config.AppConfig;
import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.ledger.LedgerEntryType;
import cz.domaci.odmeny.ledger.LedgerRepository;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.statement.Statement;
import cz.domaci.odmeny.statement.StatementConflictException;
import cz.domaci.odmeny.statement.StatementRepository;
import cz.domaci.odmeny.statement.StatementStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB adapter for completions, the pending queue, the append-only ledger
 * and monthly statements.
 *
 * <p>Key layout (ADR-0004):
 * <ul>
 *   <li>Activity: {@code PK = MEMBER#<id>},
 *       {@code SK = PERIOD#<yyyy-mm>#<TYPE>#<padded-epoch-millis>#<id>}</li>
 *   <li>Statement: {@code PK = MEMBER#<id>},
 *       {@code SK = PERIOD#<yyyy-mm>#STATEMENT#<id>}</li>
 *   <li>Pending: {@code PK = PENDING},
 *       {@code SK = CREATED#<padded-epoch-millis>#<completionId>}</li>
 *   <li>Audit: {@code PK = AUDIT#<yyyy-mm>},
 *       {@code SK = AUDIT#<padded-epoch-millis>#<id>}</li>
 * </ul>
 *
 * <p>Every state change is a DynamoDB transaction or a conditional write, which
 * makes approval, closing and payout idempotent. The audit event is part of the
 * same transaction as the change it describes.
 */
@ApplicationScoped
public class DynamoActivityRepository
        implements CompletionRepository, LedgerRepository, StatementRepository, AuditRepository {

    private static final String ENTITY_COMPLETION = "COMPLETION";
    private static final String ENTITY_LEDGER = "LEDGER";
    private static final String ENTITY_STATEMENT = "STATEMENT";
    private static final String ENTITY_AUDIT = "AUDIT";
    private static final String PENDING_PK = "PENDING";

    private final DynamoDbClient client;
    private final AppConfig config;

    @Inject
    public DynamoActivityRepository(DynamoDbClient client, AppConfig config) {
        this.client = client;
        this.config = config;
    }

    // CompletionRepository ---------------------------------------------------

    @Override
    public void createPending(Completion completion, PendingCompletion pending, AuditEvent audit) {
        transact(
                put(activityTable(), completionItem(completion)),
                put(pendingTable(), pendingItem(pending)),
                DynamoAuditItems.put(activityTable(), audit));
    }

    @Override
    public void createApproved(Completion completion, LedgerEntry ledgerEntry, AuditEvent audit) {
        transact(
                put(activityTable(), completionItem(completion)),
                put(activityTable(), ledgerItem(ledgerEntry)),
                DynamoAuditItems.put(activityTable(), audit));
    }

    @Override
    public void approve(Completion completion, LedgerEntry ledgerEntry, AuditEvent audit) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":status", AttributeValue.fromS(CompletionStatus.APPROVED.name()));
        values.put(":pending", AttributeValue.fromS(CompletionStatus.PENDING.name()));
        values.put(":approvedBy", AttributeValue.fromS(completion.approvedBy()));
        values.put(":approvedAt", AttributeValue.fromN(Long.toString(completion.approvedAt().toEpochMilli())));

        Update update = Update.builder()
                .tableName(activityTable())
                .key(completionKey(completion))
                .updateExpression(
                        "SET #status = :status, approvedBy = :approvedBy, approvedAtEpochMilli = :approvedAt "
                                + "REMOVE rejectionReason")
                .conditionExpression("attribute_exists(PK) AND #status = :pending")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(values)
                .build();

        transact(
                TransactWriteItem.builder().update(update).build(),
                put(activityTable(), ledgerItem(ledgerEntry)),
                deletePending(completion),
                DynamoAuditItems.put(activityTable(), audit));
    }

    @Override
    public void reject(Completion completion, AuditEvent audit) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":status", AttributeValue.fromS(CompletionStatus.REJECTED.name()));
        values.put(":pending", AttributeValue.fromS(CompletionStatus.PENDING.name()));
        values.put(":approvedBy", AttributeValue.fromS(completion.approvedBy()));
        values.put(":approvedAt", AttributeValue.fromN(Long.toString(completion.approvedAt().toEpochMilli())));
        values.put(":reason", AttributeValue.fromS(completion.rejectionReason()));

        Update update = Update.builder()
                .tableName(activityTable())
                .key(completionKey(completion))
                .updateExpression(
                        "SET #status = :status, approvedBy = :approvedBy, "
                                + "approvedAtEpochMilli = :approvedAt, rejectionReason = :reason")
                .conditionExpression("attribute_exists(PK) AND #status = :pending")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(values)
                .build();

        transact(
                TransactWriteItem.builder().update(update).build(),
                deletePending(completion),
                DynamoAuditItems.put(activityTable(), audit));
    }

    @Override
    public List<Completion> listCompletions(String memberId, Period period) {
        String prefix = "PERIOD#" + period.value() + "#" + ENTITY_COMPLETION + "#";
        return query(activityTable(), memberPk(memberId), prefix).stream()
                .filter(item -> ENTITY_COMPLETION.equals(string(item, "entityType")))
                .map(DynamoActivityRepository::toCompletion)
                .toList();
    }

    @Override
    public List<Completion> listCompletions(String memberId) {
        return query(activityTable(), memberPk(memberId), null).stream()
                .filter(item -> ENTITY_COMPLETION.equals(string(item, "entityType")))
                .map(DynamoActivityRepository::toCompletion)
                .toList();
    }

    @Override
    public List<PendingCompletion> listPending() {
        return query(pendingTable(), PENDING_PK, null).stream()
                .map(DynamoActivityRepository::toPendingCompletion)
                .toList();
    }

    @Override
    public Optional<PendingCompletion> findPending(String completionId) {
        return listPending().stream()
                .filter(pending -> pending.completionId().equals(completionId))
                .findFirst();
    }

    // LedgerRepository -------------------------------------------------------

    @Override
    public List<LedgerEntry> listEntries(String memberId, Period period) {
        String prefix = "PERIOD#" + period.value() + "#";
        return query(activityTable(), memberPk(memberId), prefix).stream()
                .filter(item -> ENTITY_LEDGER.equals(string(item, "entityType")))
                .map(DynamoActivityRepository::toLedgerEntry)
                .toList();
    }

    @Override
    public List<LedgerEntry> listEntries(String memberId) {
        return query(activityTable(), memberPk(memberId), null).stream()
                .filter(item -> ENTITY_LEDGER.equals(string(item, "entityType")))
                .map(DynamoActivityRepository::toLedgerEntry)
                .toList();
    }

    @Override
    public void addEntry(LedgerEntry entry, AuditEvent audit) {
        Put ledgerPut = Put.builder()
                .tableName(activityTable())
                .item(ledgerItem(entry))
                .conditionExpression("attribute_not_exists(PK)")
                .build();
        transactStatements(
                TransactWriteItem.builder().put(ledgerPut).build(),
                DynamoAuditItems.put(activityTable(), audit));
    }

    // StatementRepository ----------------------------------------------------

    @Override
    public Optional<Statement> find(String memberId, Period period) {
        String statementId = Statement.idFor(memberId, period);
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(activityTable())
                .key(Map.of(
                        "PK", AttributeValue.fromS(memberPk(memberId)),
                        "SK", AttributeValue.fromS(statementSk(period, statementId))))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toStatement(response.item()));
    }

    @Override
    public List<Statement> listStatements(String memberId) {
        return query(activityTable(), memberPk(memberId), "PERIOD#").stream()
                .filter(item -> ENTITY_STATEMENT.equals(string(item, "entityType")))
                .map(DynamoActivityRepository::toStatement)
                .toList();
    }

    @Override
    public void close(Statement statement, AuditEvent audit) {
        Put statementPut = Put.builder()
                .tableName(activityTable())
                .item(statementItem(statement))
                .conditionExpression("attribute_not_exists(PK) OR #status = :open")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":open", AttributeValue.fromS(StatementStatus.OPEN.name())))
                .build();

        transactStatements(
                TransactWriteItem.builder().put(statementPut).build(),
                DynamoAuditItems.put(activityTable(), audit));
    }

    @Override
    public void reopen(Statement statement, AuditEvent audit) {
        Put statementPut = Put.builder()
                .tableName(activityTable())
                .item(statementItem(statement))
                .conditionExpression(
                        "attribute_exists(PK) AND (#status = :closed OR #status = :paid)")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":closed", AttributeValue.fromS(StatementStatus.CLOSED.name()),
                        ":paid", AttributeValue.fromS(StatementStatus.PAID.name())))
                .build();

        transactStatements(
                TransactWriteItem.builder().put(statementPut).build(),
                DynamoAuditItems.put(activityTable(), audit));
    }

    @Override
    public void markPaid(Statement statement, LedgerEntry payout, AuditEvent audit) {
        Put statementPut = Put.builder()
                .tableName(activityTable())
                .item(statementItem(statement))
                .conditionExpression("attribute_exists(PK) AND #status = :closed")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":closed", AttributeValue.fromS(StatementStatus.CLOSED.name())))
                .build();

        transactStatements(
                TransactWriteItem.builder().put(statementPut).build(),
                put(activityTable(), ledgerItem(payout)),
                DynamoAuditItems.put(activityTable(), audit));
    }

    // AuditRepository --------------------------------------------------------

    @Override
    public void append(AuditEvent event) {
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(activityTable())
                    .item(DynamoAuditItems.item(event))
                    .conditionExpression("attribute_not_exists(PK)")
                    .build());
        } catch (ConditionalCheckFailedException e) {
            throw new IllegalStateException("Audit event already exists: " + event.id(), e);
        }
    }

    @Override
    public List<AuditEvent> listByPeriod(Period period) {
        return query(activityTable(), DynamoAuditItems.periodPrefix(period),
                DynamoAuditItems.sortKeyPrefix()).stream()
                .filter(item -> ENTITY_AUDIT.equals(string(item, "entityType")))
                .map(DynamoActivityRepository::toAuditEvent)
                .toList();
    }

    @Override
    public List<AuditEvent> listBetween(Period from, Period to) {
        List<AuditEvent> events = new ArrayList<>();
        for (Period period = from; period.compareTo(to) <= 0; period = period.next()) {
            events.addAll(listByPeriod(period));
        }
        return events;
    }

    // Helpers ----------------------------------------------------------------

    private void transact(TransactWriteItem... items) {
        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(items)
                    .build());
        } catch (TransactionCanceledException e) {
            throw new CompletionConflictException(
                    "The completion was already reviewed or changed concurrently", e);
        }
    }

    private void transactStatements(TransactWriteItem... items) {
        try {
            client.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(items)
                    .build());
        } catch (TransactionCanceledException e) {
            throw new StatementConflictException(
                    "The statement was already changed concurrently", e);
        }
    }

    private static TransactWriteItem put(String table, Map<String, AttributeValue> item) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(table)
                        .item(item)
                        .conditionExpression("attribute_not_exists(PK)")
                        .build())
                .build();
    }

    private TransactWriteItem deletePending(Completion completion) {
        return TransactWriteItem.builder()
                .delete(Delete.builder()
                        .tableName(pendingTable())
                        .key(pendingKey(completion.createdAt(), completion.id()))
                        .conditionExpression("attribute_exists(PK)")
                        .build())
                .build();
    }

    private List<Map<String, AttributeValue>> query(String table, String pk, String skPrefix) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> lastKey = null;

        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(table)
                    .expressionAttributeValues(skPrefix == null
                            ? Map.of(":pk", AttributeValue.fromS(pk))
                            : Map.of(":pk", AttributeValue.fromS(pk), ":sk", AttributeValue.fromS(skPrefix)));

            if (skPrefix == null) {
                request.keyConditionExpression("PK = :pk");
            } else {
                request.keyConditionExpression("PK = :pk AND begins_with(SK, :sk)");
            }

            if (lastKey != null && !lastKey.isEmpty()) {
                request.exclusiveStartKey(lastKey);
            }

            QueryResponse response = client.query(request.build());
            items.addAll(response.items());
            lastKey = response.lastEvaluatedKey();
        } while (lastKey != null && !lastKey.isEmpty());

        return items;
    }

    private String activityTable() {
        return config.dynamodb().tables().activity();
    }

    private String pendingTable() {
        return config.dynamodb().tables().pending();
    }

    private static Map<String, AttributeValue> completionItem(Completion completion) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(memberPk(completion.memberId())));
        item.put("SK", AttributeValue.fromS(completionSk(completion)));
        item.put("entityType", AttributeValue.fromS(ENTITY_COMPLETION));
        item.put("id", AttributeValue.fromS(completion.id()));
        item.put("householdId", AttributeValue.fromS(completion.householdId()));
        item.put("memberId", AttributeValue.fromS(completion.memberId()));
        item.put("taskId", AttributeValue.fromS(completion.taskId()));
        item.put("taskName", AttributeValue.fromS(completion.taskName()));
        item.put("completedAtEpochMilli", AttributeValue.fromN(
                Long.toString(completion.completedAt().toEpochMilli())));
        item.put("createdAtEpochMilli", AttributeValue.fromN(
                Long.toString(completion.createdAt().toEpochMilli())));
        item.put("period", AttributeValue.fromS(completion.period().value()));
        item.put("status", AttributeValue.fromS(completion.status().name()));
        item.put("rewardMinor", AttributeValue.fromN(Long.toString(completion.reward().minor())));
        putString(item, "note", completion.note());
        putString(item, "approvedBy", completion.approvedBy());
        if (completion.approvedAt() != null) {
            item.put("approvedAtEpochMilli", AttributeValue.fromN(
                    Long.toString(completion.approvedAt().toEpochMilli())));
        }
        putString(item, "rejectionReason", completion.rejectionReason());
        return item;
    }

    private static Map<String, AttributeValue> ledgerItem(LedgerEntry entry) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(memberPk(entry.memberId())));
        item.put("SK", AttributeValue.fromS(ledgerSk(entry)));
        item.put("entityType", AttributeValue.fromS(ENTITY_LEDGER));
        item.put("id", AttributeValue.fromS(entry.id()));
        item.put("householdId", AttributeValue.fromS(entry.householdId()));
        item.put("memberId", AttributeValue.fromS(entry.memberId()));
        item.put("period", AttributeValue.fromS(entry.period().value()));
        item.put("type", AttributeValue.fromS(entry.type().name()));
        item.put("amountMinor", AttributeValue.fromN(Long.toString(entry.amount().minor())));
        item.put("description", AttributeValue.fromS(entry.description()));
        item.put("referenceId", AttributeValue.fromS(entry.referenceId()));
        item.put("createdBy", AttributeValue.fromS(entry.createdBy()));
        item.put("createdAtEpochMilli", AttributeValue.fromN(
                Long.toString(entry.createdAt().toEpochMilli())));
        return item;
    }

    private static Map<String, AttributeValue> statementItem(Statement statement) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(memberPk(statement.memberId())));
        item.put("SK", AttributeValue.fromS(statementSk(statement.period(), statement.id())));
        item.put("entityType", AttributeValue.fromS(ENTITY_STATEMENT));
        item.put("id", AttributeValue.fromS(statement.id()));
        item.put("householdId", AttributeValue.fromS(statement.householdId()));
        item.put("memberId", AttributeValue.fromS(statement.memberId()));
        item.put("period", AttributeValue.fromS(statement.period().value()));
        item.put("baseAllowanceMinor", AttributeValue.fromN(Long.toString(statement.baseAllowance().minor())));
        item.put("taskBonusMinor", AttributeValue.fromN(Long.toString(statement.taskBonus().minor())));
        item.put("adjustmentsMinor", AttributeValue.fromN(Long.toString(statement.adjustments().minor())));
        item.put("totalMinor", AttributeValue.fromN(Long.toString(statement.total().minor())));
        item.put("paidMinor", AttributeValue.fromN(Long.toString(statement.paid().minor())));
        item.put("status", AttributeValue.fromS(statement.status().name()));
        putInstant(item, "closedAtEpochMilli", statement.closedAt());
        putString(item, "closedBy", statement.closedBy());
        putInstant(item, "paidAtEpochMilli", statement.paidAt());
        putString(item, "paidBy", statement.paidBy());
        putInstant(item, "reopenedAtEpochMilli", statement.reopenedAt());
        putString(item, "reopenedBy", statement.reopenedBy());
        putString(item, "reopenReason", statement.reopenReason());
        return item;
    }

    private static Map<String, AttributeValue> pendingItem(PendingCompletion pending) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(PENDING_PK));
        item.put("SK", AttributeValue.fromS(pendingSk(pending.createdAt(), pending.completionId())));
        item.put("completionId", AttributeValue.fromS(pending.completionId()));
        item.put("householdId", AttributeValue.fromS(pending.householdId()));
        item.put("memberId", AttributeValue.fromS(pending.memberId()));
        item.put("memberName", AttributeValue.fromS(pending.memberName()));
        item.put("taskId", AttributeValue.fromS(pending.taskId()));
        item.put("taskName", AttributeValue.fromS(pending.taskName()));
        item.put("rewardMinor", AttributeValue.fromN(Long.toString(pending.reward().minor())));
        item.put("completedAtEpochMilli", AttributeValue.fromN(
                Long.toString(pending.completedAt().toEpochMilli())));
        item.put("period", AttributeValue.fromS(pending.period().value()));
        putString(item, "note", pending.note());
        item.put("createdAtEpochMilli", AttributeValue.fromN(
                Long.toString(pending.createdAt().toEpochMilli())));
        return item;
    }

    private static Completion toCompletion(Map<String, AttributeValue> item) {
        return new Completion(
                string(item, "id"),
                string(item, "householdId"),
                string(item, "memberId"),
                string(item, "taskId"),
                string(item, "taskName"),
                instant(item, "completedAtEpochMilli"),
                instant(item, "createdAtEpochMilli"),
                Period.parse(string(item, "period")),
                CompletionStatus.valueOf(string(item, "status")),
                Money.minor(Long.parseLong(item.get("rewardMinor").n())),
                string(item, "note"),
                string(item, "approvedBy"),
                instant(item, "approvedAtEpochMilli"),
                string(item, "rejectionReason"));
    }

    private static LedgerEntry toLedgerEntry(Map<String, AttributeValue> item) {
        return new LedgerEntry(
                string(item, "id"),
                string(item, "householdId"),
                string(item, "memberId"),
                Period.parse(string(item, "period")),
                LedgerEntryType.valueOf(string(item, "type")),
                Money.minor(Long.parseLong(item.get("amountMinor").n())),
                string(item, "description"),
                string(item, "referenceId"),
                string(item, "createdBy"),
                instant(item, "createdAtEpochMilli"));
    }

    private static Statement toStatement(Map<String, AttributeValue> item) {
        return new Statement(
                string(item, "id"),
                string(item, "householdId"),
                string(item, "memberId"),
                Period.parse(string(item, "period")),
                Money.minor(Long.parseLong(item.get("baseAllowanceMinor").n())),
                Money.minor(Long.parseLong(item.get("taskBonusMinor").n())),
                Money.minor(Long.parseLong(item.get("adjustmentsMinor").n())),
                Money.minor(Long.parseLong(item.get("totalMinor").n())),
                Money.minor(Long.parseLong(item.get("paidMinor").n())),
                StatementStatus.valueOf(string(item, "status")),
                instant(item, "closedAtEpochMilli"),
                string(item, "closedBy"),
                instant(item, "paidAtEpochMilli"),
                string(item, "paidBy"),
                instant(item, "reopenedAtEpochMilli"),
                string(item, "reopenedBy"),
                string(item, "reopenReason"));
    }

    private static AuditEvent toAuditEvent(Map<String, AttributeValue> item) {
        return new AuditEvent(
                string(item, "id"),
                string(item, "householdId"),
                Period.parse(string(item, "period")),
                instant(item, "occurredAtEpochMilli"),
                string(item, "actorId"),
                string(item, "actorName"),
                AuditAction.valueOf(string(item, "action")),
                string(item, "memberId"),
                string(item, "memberName"),
                string(item, "details"));
    }

    private static PendingCompletion toPendingCompletion(Map<String, AttributeValue> item) {
        return new PendingCompletion(
                string(item, "completionId"),
                string(item, "householdId"),
                string(item, "memberId"),
                string(item, "memberName"),
                string(item, "taskId"),
                string(item, "taskName"),
                Money.minor(Long.parseLong(item.get("rewardMinor").n())),
                instant(item, "completedAtEpochMilli"),
                Period.parse(string(item, "period")),
                string(item, "note"),
                instant(item, "createdAtEpochMilli"));
    }

    private static Map<String, AttributeValue> completionKey(Completion completion) {
        return Map.of(
                "PK", AttributeValue.fromS(memberPk(completion.memberId())),
                "SK", AttributeValue.fromS(completionSk(completion)));
    }

    private static Map<String, AttributeValue> pendingKey(Instant createdAt, String completionId) {
        return Map.of(
                "PK", AttributeValue.fromS(PENDING_PK),
                "SK", AttributeValue.fromS(pendingSk(createdAt, completionId)));
    }

    private static String completionSk(Completion completion) {
        return "PERIOD#" + completion.period().value()
                + "#" + ENTITY_COMPLETION
                + "#" + paddedMillis(completion.completedAt())
                + "#" + completion.id();
    }

    private static String ledgerSk(LedgerEntry entry) {
        return "PERIOD#" + entry.period().value()
                + "#" + entry.type().name()
                + "#" + paddedMillis(entry.createdAt())
                + "#" + entry.id();
    }

    private static String statementSk(Period period, String statementId) {
        return "PERIOD#" + period.value() + "#" + ENTITY_STATEMENT + "#" + statementId;
    }

    private static String pendingSk(Instant createdAt, String completionId) {
        return "CREATED#" + paddedMillis(createdAt) + "#" + completionId;
    }

    private static String memberPk(String memberId) {
        return "MEMBER#" + memberId;
    }

    private static String paddedMillis(Instant instant) {
        return String.format(Locale.ROOT, "%019d", instant.toEpochMilli());
    }

    private static void putString(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, AttributeValue.fromS(value));
        }
    }

    private static void putInstant(Map<String, AttributeValue> item, String name, Instant value) {
        if (value != null) {
            item.put(name, AttributeValue.fromN(Long.toString(value.toEpochMilli())));
        }
    }

    private static String string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static Instant instant(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? null : Instant.ofEpochMilli(Long.parseLong(value.n()));
    }
}

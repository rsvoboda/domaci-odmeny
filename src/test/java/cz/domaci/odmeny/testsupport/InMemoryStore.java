package cz.domaci.odmeny.testsupport;

import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.audit.AuditRepository;
import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionConflictException;
import cz.domaci.odmeny.completion.CompletionRepository;
import cz.domaci.odmeny.completion.PendingCompletion;
import cz.domaci.odmeny.household.Household;
import cz.domaci.odmeny.household.HouseholdRepository;
import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.ledger.LedgerRepository;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberRepository;
import cz.domaci.odmeny.member.MemberRole;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.statement.Statement;
import cz.domaci.odmeny.statement.StatementConflictException;
import cz.domaci.odmeny.statement.StatementRepository;
import cz.domaci.odmeny.task.Task;
import cz.domaci.odmeny.task.TaskRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only in-memory implementation of every repository. It replaces the
 * DynamoDB adapters through CDI so the test suite stays hermetic.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class InMemoryStore implements MemberRepository, HouseholdRepository, TaskRepository,
        CompletionRepository, LedgerRepository, StatementRepository, AuditRepository {

    public static final String HOUSEHOLD_ID = "default";
    public static final String PARENT_1 = "parent-1";
    public static final String CHILD_1 = "child-1";
    public static final String CHILD_2 = "child-2";

    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Map<String, Completion> completions = new LinkedHashMap<>();
    private final Map<String, LedgerEntry> ledger = new LinkedHashMap<>();
    private final Map<String, PendingCompletion> pending = new LinkedHashMap<>();
    private final Map<String, Statement> statements = new LinkedHashMap<>();
    private final Map<String, AuditEvent> auditEvents = new LinkedHashMap<>();
    private Household household;

    @PostConstruct
    void init() {
        reset();
    }

    public synchronized void reset() {
        members.clear();
        tasks.clear();
        completions.clear();
        ledger.clear();
        pending.clear();
        statements.clear();
        auditEvents.clear();

        household = new Household(HOUSEHOLD_ID, "Naše domácnost", "CZK", "Europe/Prague");

        putMember(new Member(PARENT_1, HOUSEHOLD_ID, "dev-parent-1",
                "Rodič 1", MemberRole.PARENT, Money.ZERO, true));
        putMember(new Member("parent-2", HOUSEHOLD_ID, "dev-parent-2",
                "Rodič 2", MemberRole.PARENT, Money.ZERO, true));
        putMember(new Member(CHILD_1, HOUSEHOLD_ID, "dev-child-1",
                "Dítě 1", MemberRole.CHILD, Money.czk(50), true));
        putMember(new Member(CHILD_2, HOUSEHOLD_ID, "dev-child-2",
                "Dítě 2", MemberRole.CHILD, Money.czk(50), true));

        putTask(new Task("odpadky", HOUSEHOLD_ID, "Odpadky", "Vynesení odpadků",
                Money.czk(10), true, true, 1));
        putTask(new Task("mycka", HOUSEHOLD_ID, "Myčka", "Uklizení a spuštění myčky",
                Money.czk(10), true, true, 2));
        putTask(new Task("vysavani", HOUSEHOLD_ID, "Vysávání", "Vysávání podlah",
                Money.czk(20), true, true, 3));
    }

    public synchronized void putMember(Member member) {
        members.put(member.id(), member);
    }

    public synchronized void putTask(Task task) {
        tasks.put(task.id(), task);
    }

    public synchronized List<LedgerEntry> ledger(String memberId, Period period) {
        return ledger.values().stream()
                .filter(entry -> entry.memberId().equals(memberId) && entry.period().equals(period))
                .toList();
    }

    public synchronized List<PendingCompletion> pendingItems() {
        return new ArrayList<>(pending.values());
    }

    public synchronized Optional<Completion> completion(String memberId, String completionId) {
        return Optional.ofNullable(completions.get(completionKey(memberId, completionId)));
    }

    public synchronized Optional<Statement> statement(String memberId, Period period) {
        return Optional.ofNullable(statements.get(statementKey(memberId, period)));
    }

    public synchronized List<Statement> statementItems() {
        return new ArrayList<>(statements.values());
    }

    public synchronized List<AuditEvent> auditEvents() {
        return new ArrayList<>(auditEvents.values());
    }

    public synchronized List<AuditEvent> auditEvents(Period period) {
        return auditEvents.values().stream()
                .filter(event -> event.period().equals(period))
                .toList();
    }

    // MemberRepository -------------------------------------------------------

    @Override
    public synchronized List<Member> listByHousehold(String householdId) {
        return members.values().stream()
                .filter(member -> member.householdId().equals(householdId))
                .toList();
    }

    @Override
    public synchronized Optional<Member> findMember(String householdId, String memberId) {
        return Optional.ofNullable(members.get(memberId))
                .filter(member -> member.householdId().equals(householdId));
    }

    @Override
    public synchronized Optional<Member> findByCognitoSub(String householdId, String cognitoSub) {
        return members.values().stream()
                .filter(member -> member.householdId().equals(householdId))
                .filter(member -> member.cognitoSub().equals(cognitoSub))
                .findFirst();
    }

    @Override
    public synchronized void put(Member member) {
        putMember(member);
    }

    // HouseholdRepository ----------------------------------------------------

    @Override
    public synchronized Optional<Household> findById(String id) {
        return household != null && household.id().equals(id) ? Optional.of(household) : Optional.empty();
    }

    @Override
    public synchronized void put(Household household) {
        this.household = household;
    }

    // TaskRepository ---------------------------------------------------------

    @Override
    public synchronized List<Task> listTasks(String householdId) {
        return tasks.values().stream()
                .filter(task -> task.householdId().equals(householdId))
                .toList();
    }

    @Override
    public synchronized Optional<Task> findById(String householdId, String taskId) {
        return Optional.ofNullable(tasks.get(taskId))
                .filter(task -> task.householdId().equals(householdId));
    }

    @Override
    public synchronized void put(Task task) {
        putTask(task);
    }

    // CompletionRepository ---------------------------------------------------

    @Override
    public synchronized void createPending(Completion completion, PendingCompletion pendingCompletion,
            AuditEvent audit) {
        String key = completionKey(completion.memberId(), completion.id());
        if (completions.containsKey(key) || pending.containsKey(completion.id())) {
            throw new CompletionConflictException("Completion already exists");
        }
        completions.put(key, completion);
        pending.put(completion.id(), pendingCompletion);
        putAudit(audit);
    }

    @Override
    public synchronized void createApproved(Completion completion, LedgerEntry ledgerEntry, AuditEvent audit) {
        String key = completionKey(completion.memberId(), completion.id());
        if (completions.containsKey(key) || ledger.containsKey(ledgerEntry.id())) {
            throw new CompletionConflictException("Completion already exists");
        }
        completions.put(key, completion);
        ledger.put(ledgerEntry.id(), ledgerEntry);
        putAudit(audit);
    }

    @Override
    public synchronized void approve(Completion completion, LedgerEntry ledgerEntry, AuditEvent audit) {
        Completion existing = completions.get(completionKey(completion.memberId(), completion.id()));
        if (existing == null || !existing.isPending()) {
            throw new CompletionConflictException("Completion is no longer pending");
        }
        if (ledger.containsKey(ledgerEntry.id())) {
            throw new CompletionConflictException("Reward was already written");
        }
        completions.put(completionKey(completion.memberId(), completion.id()), completion);
        ledger.put(ledgerEntry.id(), ledgerEntry);
        pending.remove(completion.id());
        putAudit(audit);
    }

    @Override
    public synchronized void reject(Completion completion, AuditEvent audit) {
        Completion existing = completions.get(completionKey(completion.memberId(), completion.id()));
        if (existing == null || !existing.isPending()) {
            throw new CompletionConflictException("Completion is no longer pending");
        }
        completions.put(completionKey(completion.memberId(), completion.id()), completion);
        pending.remove(completion.id());
        putAudit(audit);
    }

    @Override
    public synchronized List<Completion> listCompletions(String memberId, Period period) {
        return completions.values().stream()
                .filter(completion -> completion.memberId().equals(memberId))
                .filter(completion -> completion.period().equals(period))
                .toList();
    }

    @Override
    public synchronized List<Completion> listCompletions(String memberId) {
        return completions.values().stream()
                .filter(completion -> completion.memberId().equals(memberId))
                .toList();
    }

    @Override
    public synchronized List<PendingCompletion> listPending() {
        return new ArrayList<>(pending.values());
    }

    @Override
    public synchronized Optional<PendingCompletion> findPending(String completionId) {
        return Optional.ofNullable(pending.get(completionId));
    }

    // LedgerRepository -------------------------------------------------------

    @Override
    public synchronized List<LedgerEntry> listEntries(String memberId, Period period) {
        return ledger.values().stream()
                .filter(entry -> entry.memberId().equals(memberId))
                .filter(entry -> entry.period().equals(period))
                .toList();
    }

    @Override
    public synchronized List<LedgerEntry> listEntries(String memberId) {
        return ledger.values().stream()
                .filter(entry -> entry.memberId().equals(memberId))
                .toList();
    }

    @Override
    public synchronized void addEntry(LedgerEntry entry, AuditEvent audit) {
        if (ledger.containsKey(entry.id())) {
            throw new IllegalStateException("Ledger entry already exists: " + entry.id());
        }
        ledger.put(entry.id(), entry);
        putAudit(audit);
    }

    // StatementRepository ----------------------------------------------------

    @Override
    public synchronized Optional<Statement> find(String memberId, Period period) {
        return Optional.ofNullable(statements.get(statementKey(memberId, period)));
    }

    @Override
    public synchronized List<Statement> listStatements(String memberId) {
        return statements.values().stream()
                .filter(statement -> statement.memberId().equals(memberId))
                .toList();
    }

    @Override
    public synchronized void close(Statement statement, AuditEvent audit) {
        Statement existing = statements.get(statementKey(statement.memberId(), statement.period()));
        if (existing != null && !existing.isOpen()) {
            throw new StatementConflictException("The month is already closed");
        }
        statements.put(statementKey(statement.memberId(), statement.period()), statement);
        putAudit(audit);
    }

    @Override
    public synchronized void reopen(Statement statement, AuditEvent audit) {
        Statement existing = statements.get(statementKey(statement.memberId(), statement.period()));
        if (existing == null || existing.isOpen()) {
            throw new StatementConflictException("The month is not closed");
        }
        statements.put(statementKey(statement.memberId(), statement.period()), statement);
        putAudit(audit);
    }

    @Override
    public synchronized void markPaid(Statement statement, LedgerEntry payout, AuditEvent audit) {
        Statement existing = statements.get(statementKey(statement.memberId(), statement.period()));
        if (existing == null || !existing.isClosed()) {
            throw new StatementConflictException("The month is not closed");
        }
        if (ledger.containsKey(payout.id())) {
            throw new StatementConflictException("The payout was already written");
        }
        statements.put(statementKey(statement.memberId(), statement.period()), statement);
        ledger.put(payout.id(), payout);
        putAudit(audit);
    }

    // AuditRepository --------------------------------------------------------

    @Override
    public synchronized void append(AuditEvent event) {
        putAudit(event);
    }

    @Override
    public synchronized List<AuditEvent> listByPeriod(Period period) {
        return auditEvents.values().stream()
                .filter(event -> event.period().equals(period))
                .toList();
    }

    @Override
    public synchronized List<AuditEvent> listBetween(Period from, Period to) {
        return auditEvents.values().stream()
                .filter(event -> event.period().compareTo(from) >= 0 && event.period().compareTo(to) <= 0)
                .toList();
    }

    private void putAudit(AuditEvent audit) {
        if (auditEvents.containsKey(audit.id())) {
            throw new IllegalStateException("Audit event already exists: " + audit.id());
        }
        auditEvents.put(audit.id(), audit);
    }

    private static String completionKey(String memberId, String completionId) {
        return memberId + "|" + completionId;
    }

    private static String statementKey(String memberId, Period period) {
        return memberId + "|" + period.value();
    }
}

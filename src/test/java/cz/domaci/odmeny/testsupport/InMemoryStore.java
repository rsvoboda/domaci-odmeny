package cz.domaci.odmeny.testsupport;

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
        CompletionRepository, LedgerRepository {

    public static final String HOUSEHOLD_ID = "default";
    public static final String PARENT_1 = "parent-1";
    public static final String CHILD_1 = "child-1";
    public static final String CHILD_2 = "child-2";

    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final Map<String, Completion> completions = new LinkedHashMap<>();
    private final Map<String, LedgerEntry> ledger = new LinkedHashMap<>();
    private final Map<String, PendingCompletion> pending = new LinkedHashMap<>();
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

    // MemberRepository -------------------------------------------------------

    @Override
    public synchronized List<Member> listByHousehold(String householdId) {
        return members.values().stream()
                .filter(member -> member.householdId().equals(householdId))
                .toList();
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
    public synchronized void createPending(Completion completion, PendingCompletion pendingCompletion) {
        String key = completionKey(completion.memberId(), completion.id());
        if (completions.containsKey(key) || pending.containsKey(completion.id())) {
            throw new CompletionConflictException("Completion already exists");
        }
        completions.put(key, completion);
        pending.put(completion.id(), pendingCompletion);
    }

    @Override
    public synchronized void createApproved(Completion completion, LedgerEntry ledgerEntry) {
        String key = completionKey(completion.memberId(), completion.id());
        if (completions.containsKey(key) || ledger.containsKey(ledgerEntry.id())) {
            throw new CompletionConflictException("Completion already exists");
        }
        completions.put(key, completion);
        ledger.put(ledgerEntry.id(), ledgerEntry);
    }

    @Override
    public synchronized void approve(Completion completion, LedgerEntry ledgerEntry) {
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
    }

    @Override
    public synchronized void reject(Completion completion) {
        Completion existing = completions.get(completionKey(completion.memberId(), completion.id()));
        if (existing == null || !existing.isPending()) {
            throw new CompletionConflictException("Completion is no longer pending");
        }
        completions.put(completionKey(completion.memberId(), completion.id()), completion);
        pending.remove(completion.id());
    }

    @Override
    public synchronized List<Completion> listCompletions(String memberId, Period period) {
        return completions.values().stream()
                .filter(completion -> completion.memberId().equals(memberId))
                .filter(completion -> completion.period().equals(period))
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

    private static String completionKey(String memberId, String completionId) {
        return memberId + "|" + completionId;
    }
}

package cz.domaci.odmeny.completion;

import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.ledger.EarningsService;
import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.ledger.LedgerEntryType;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.task.Task;
import cz.domaci.odmeny.testsupport.InMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CompletionServiceTest {

    @Inject
    InMemoryStore store;

    @Inject
    CompletionService completions;

    @Inject
    MemberService members;

    @Inject
    HouseholdService household;

    @Inject
    EarningsService earnings;

    private Member child;
    private Member sibling;
    private Member parent;

    @BeforeEach
    void setUp() {
        store.reset();
        child = member("dev-child-1");
        sibling = member("dev-child-2");
        parent = member("dev-parent-1");
    }

    @Test
    void pendingChoreCreatesQueueItemWithoutLedger() {
        Completion completion = completions.logChore(child, "odpadky", "  vynesl jsem to  ");

        assertEquals(CompletionStatus.PENDING, completion.status());
        assertEquals(Money.czk(10), completion.reward());
        assertEquals("vynesl jsem to", completion.note());
        assertEquals(1, store.pendingItems().size());
        assertTrue(store.ledger(child.id(), period()).isEmpty());
    }

    @Test
    void autoApprovedChoreCreatesLedgerEntryImmediately() {
        store.putTask(new Task("auto", InMemoryStore.HOUSEHOLD_ID, "Automatická práce", "",
                Money.czk(5), false, true, 10));

        Completion completion = completions.logChore(child, "auto", null);

        assertEquals(CompletionStatus.APPROVED, completion.status());
        assertTrue(store.pendingItems().isEmpty());
        List<LedgerEntry> entries = store.ledger(child.id(), period());
        assertEquals(1, entries.size());
        assertEquals(LedgerEntryType.TASK_REWARD, entries.get(0).type());
        assertEquals(Money.czk(5), entries.get(0).amount());
        assertEquals(completion.id(), entries.get(0).referenceId());
    }

    @Test
    void approveWritesLedgerClearsQueueAndUpdatesEarnings() {
        Completion pending = completions.logChore(child, "odpadky", null);

        Completion approved = completions.approve(parent, pending.id());

        assertEquals(CompletionStatus.APPROVED, approved.status());
        assertEquals(parent.id(), approved.approvedBy());
        assertTrue(store.pendingItems().isEmpty());
        assertEquals(1, store.ledger(child.id(), period()).size());
        assertEquals(Money.czk(60), earnings.currentMonth(child).total());
    }

    @Test
    void approvingTwiceCreditsOnlyOnce() {
        Completion pending = completions.logChore(child, "odpadky", null);
        completions.approve(parent, pending.id());

        assertThrows(CompletionConflictException.class,
                () -> completions.approve(parent, pending.id()));
        assertEquals(1, store.ledger(child.id(), period()).size());
    }

    @Test
    void rejectRequiresAReason() {
        Completion pending = completions.logChore(child, "odpadky", null);

        assertThrows(InvalidCompletionException.class,
                () -> completions.reject(parent, pending.id(), "   "));
    }

    @Test
    void rejectionDoesNotWriteALedgerEntry() {
        Completion pending = completions.logChore(child, "odpadky", null);

        Completion rejected = completions.reject(parent, pending.id(), "Nebylo to hotové");

        assertEquals(CompletionStatus.REJECTED, rejected.status());
        assertEquals("Nebylo to hotové", rejected.rejectionReason());
        assertTrue(store.pendingItems().isEmpty());
        assertTrue(store.ledger(child.id(), period()).isEmpty());
        assertEquals(Money.czk(50), earnings.currentMonth(child).total());
    }

    @Test
    void parentsCannotLogChores() {
        assertThrows(InvalidCompletionException.class,
                () -> completions.logChore(parent, "odpadky", null));
    }

    @Test
    void inactiveTaskIsNotAvailable() {
        store.putTask(new Task("off", InMemoryStore.HOUSEHOLD_ID, "Vypnutá práce", "",
                Money.czk(10), true, false, 10));

        assertThrows(TaskNotAvailableException.class,
                () -> completions.logChore(child, "off", null));
    }

    @Test
    void childHistoryContainsOnlyOwnCompletions() {
        completions.logChore(sibling, "odpadky", "sibling-secret");
        completions.logChore(child, "mycka", "my-own");

        List<Completion> history = completions.currentMonth(child);

        assertEquals(1, history.size());
        assertEquals("my-own", history.get(0).note());
        assertFalse(history.stream().anyMatch(item -> "sibling-secret".equals(item.note())));
    }

    private Member member(String cognitoSub) {
        return members.findByCognitoSub(cognitoSub).orElseThrow();
    }

    private Period period() {
        return household.currentPeriod();
    }
}

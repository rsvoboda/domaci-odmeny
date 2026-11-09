package cz.domaci.odmeny.audit;

import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionConflictException;
import cz.domaci.odmeny.completion.CompletionService;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.statement.StatementService;
import cz.domaci.odmeny.task.Task;
import cz.domaci.odmeny.testsupport.InMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit trail must describe every money-relevant state change and must be
 * written together with it, so a failed change leaves no audit event behind.
 */
@QuarkusTest
class AuditTrailTest {

    @Inject
    InMemoryStore store;

    @Inject
    CompletionService completions;

    @Inject
    StatementService statements;

    @Inject
    MemberService members;

    @Inject
    HouseholdService household;

    @Inject
    AuditService audits;

    private Member child;
    private Member parent;

    @BeforeEach
    void setUp() {
        store.reset();
        child = member("dev-child-1");
        parent = member("dev-parent-1");
    }

    @Test
    void loggingAPendingChoreRecordsWhoAndWhat() {
        completions.logChore(child, "odpadky", "vynesl jsem to");

        List<AuditEvent> events = store.auditEvents(period());

        assertEquals(1, events.size());
        AuditEvent event = events.get(0);
        assertEquals(AuditAction.CHORE_LOGGED, event.action());
        assertEquals(child.id(), event.actorId());
        assertEquals(child.id(), event.memberId());
        assertTrue(event.details().contains("Odpadky"));
        assertTrue(event.details().contains("vynesl jsem to"));
    }

    @Test
    void autoApprovedChoreRecordsTheCredit() {
        store.putTask(new Task("auto", InMemoryStore.HOUSEHOLD_ID, "Automatická práce", "",
                Money.czk(5), false, true, 10));

        completions.logChore(child, "auto", null);

        List<AuditEvent> events = store.auditEvents(period());
        assertEquals(1, events.size());
        assertEquals(AuditAction.CHORE_AUTO_APPROVED, events.get(0).action());
        assertEquals(child.id(), events.get(0).memberId());
    }

    @Test
    void approvalAndRejectionAreRecorded() {
        Completion approved = completions.logChore(child, "odpadky", null);
        completions.approve(parent, approved.id());

        Completion rejected = completions.logChore(child, "mycka", null);
        completions.reject(parent, rejected.id(), "Nebylo to hotové");

        List<AuditEvent> events = store.auditEvents(period());
        assertEquals(List.of(
                        AuditAction.CHORE_LOGGED,
                        AuditAction.CHORE_APPROVED,
                        AuditAction.CHORE_LOGGED,
                        AuditAction.CHORE_REJECTED),
                events.stream().map(AuditEvent::action).toList());
        assertEquals(parent.id(), events.get(1).actorId());
        assertTrue(events.get(3).details().contains("Nebylo to hotové"));
    }

    @Test
    void failedDoubleApprovalDoesNotAddAnAuditEvent() {
        Completion pending = completions.logChore(child, "odpadky", null);
        completions.approve(parent, pending.id());
        int before = store.auditEvents().size();

        assertThrows(CompletionConflictException.class,
                () -> completions.approve(parent, pending.id()));

        assertEquals(before, store.auditEvents().size());
    }

    @Test
    void correctionClosePayoutAndReopenAreRecorded() {
        Completion pending = completions.logChore(child, "odpadky", null);
        completions.approve(parent, pending.id());

        statements.addAdjustment(parent, child, period(), Money.czk(20), "Bonus");
        statements.close(parent, child, period());
        statements.markPaid(parent, child, period());
        statements.reopen(parent, child, period(), "Chyba ve výpočtu");

        List<AuditAction> actions = store.auditEvents(period()).stream()
                .map(AuditEvent::action)
                .toList();

        assertEquals(List.of(
                AuditAction.CHORE_LOGGED,
                AuditAction.CHORE_APPROVED,
                AuditAction.ADJUSTMENT_ADDED,
                AuditAction.MONTH_CLOSED,
                AuditAction.MONTH_PAID,
                AuditAction.MONTH_REOPENED), actions);

        AuditEvent adjustment = store.auditEvents(period()).get(2);
        assertTrue(adjustment.details().contains("20 Kč"));
        assertTrue(adjustment.details().contains("Bonus"));

        AuditEvent reopen = store.auditEvents(period()).get(5);
        assertEquals("Chyba ve výpočtu", reopen.details());
        assertEquals(parent.id(), reopen.actorId());
    }

    @Test
    void auditIsPartitionedByPeriodAndListedNewestFirst() {
        Period current = period();
        Period previous = current.previous();

        store.append(new AuditEvent("old", InMemoryStore.HOUSEHOLD_ID, previous, household.now(),
                parent.id(), parent.displayName(), AuditAction.MONTH_CLOSED,
                child.id(), child.displayName(), null));
        completions.logChore(child, "odpadky", null);

        List<AuditEvent> currentEvents = audits.list(current);

        assertEquals(1, currentEvents.size());
        assertEquals(AuditAction.CHORE_LOGGED, currentEvents.get(0).action());
        assertEquals(2, audits.listAll(previous).size());
    }

    private Member member(String cognitoSub) {
        return members.findByCognitoSub(cognitoSub).orElseThrow();
    }

    private Period period() {
        return household.currentPeriod();
    }
}

package cz.domaci.odmeny.settings;

import cz.domaci.odmeny.audit.AuditAction;
import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.member.MemberRole;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.task.Task;
import cz.domaci.odmeny.task.TaskService;
import cz.domaci.odmeny.testsupport.InMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SettingsServiceTest {

    @Inject
    InMemoryStore store;

    @Inject
    SettingsService settings;

    @Inject
    MemberService members;

    @Inject
    TaskService tasks;

    @Inject
    HouseholdService household;

    private Member parent;
    private Member child;

    @BeforeEach
    void setUp() {
        store.reset();
        parent = member("dev-parent-1");
        child = member("dev-child-1");
    }

    @Test
    void renamingTheHouseholdStoresItAndWritesAHouseholdWideAuditEvent() {
        settings.renameHousehold(parent, "  Nová domácnost  ");

        assertEquals("Nová domácnost", household.name());

        List<AuditEvent> audits = store.auditEvents();
        assertEquals(1, audits.size());
        AuditEvent event = audits.get(0);
        assertEquals(AuditAction.HOUSEHOLD_RENAMED, event.action());
        assertNull(event.memberId());
        assertTrue(event.householdWide());
        assertTrue(event.details().contains("Nová domácnost"));
    }

    @Test
    void renamingAMemberKeepsTheAllowanceAndAuditsOnlyTheName() {
        Member updated = settings.updateMember(parent, child.id(), "Anička", child.baseAllowance(), child.active());

        assertEquals("Anička", updated.displayName());
        assertEquals(child.baseAllowance(), updated.baseAllowance());
        assertTrue(updated.active());

        AuditEvent event = onlyAudit();
        assertEquals(AuditAction.MEMBER_RENAMED, event.action());
        assertEquals(child.id(), event.memberId());
        assertEquals("Anička", event.memberName());
        assertTrue(event.details().contains("Dítě 1 → Anička"));
    }

    @Test
    void updatingAChildStoresAllowanceAndActiveFlag() {
        Member updated = settings.updateMember(parent, child.id(), "Anička", Money.czk(80), false);

        assertEquals(Money.czk(80), updated.baseAllowance());
        assertFalse(updated.active());

        AuditEvent event = onlyAudit();
        assertEquals(AuditAction.MEMBER_UPDATED, event.action());
        assertTrue(event.details().contains("Základ: 50 Kč → 80 Kč"));
        assertTrue(event.details().contains("Aktivní: ano → ne"));
    }

    @Test
    void aParentsAllowanceAndActiveFlagCannotBeChangedFromSettings() {
        Member updated = settings.updateMember(parent, parent.id(), "Táta", Money.czk(999), false);

        assertEquals("Táta", updated.displayName());
        assertEquals(Money.ZERO, updated.baseAllowance());
        assertTrue(updated.active());
        assertEquals(MemberRole.PARENT, updated.role());
    }

    @Test
    void invalidInputIsRejectedBeforeAnythingIsWritten() {
        assertThrows(InvalidSettingsException.class,
                () -> settings.updateMember(parent, child.id(), "   ", Money.czk(50), true));
        assertThrows(InvalidSettingsException.class,
                () -> settings.updateMember(parent, child.id(), "Anička", Money.czk(-1), true));
        assertThrows(InvalidSettingsException.class,
                () -> settings.updateMember(parent, "missing", "Anička", Money.czk(50), true));
        assertTrue(store.auditEvents().isEmpty());
    }

    @Test
    void anUnchangedMemberProducesNoAuditEvent() {
        Member updated = settings.updateMember(parent, child.id(), child.displayName(),
                child.baseAllowance(), child.active());

        assertEquals(child, updated);
        assertTrue(store.auditEvents().isEmpty());
    }

    @Test
    void creatingATaskAppendsItToTheCatalogWithTheNextSortOrder() {
        Task created = settings.saveTask(parent, null, "  Umytí nádobí  ", "Kuchyň",
                Money.czk(15), true, true);

        assertEquals("Umytí nádobí", created.name());
        assertEquals(4, created.sortOrder());
        assertTrue(tasks.listAll().stream().anyMatch(task -> task.id().equals(created.id())));

        AuditEvent event = onlyAudit();
        assertEquals(AuditAction.TASK_CREATED, event.action());
        assertNull(event.memberId());
        assertTrue(event.details().contains("Umytí nádobí"));
    }

    @Test
    void updatingATaskKeepsItsIdAndSortOrder() {
        Task existing = tasks.listAll().get(0);

        Task updated = settings.saveTask(parent, existing.id(), existing.name(), existing.description(),
                Money.czk(25), false, false);

        assertEquals(existing.id(), updated.id());
        assertEquals(existing.sortOrder(), updated.sortOrder());
        assertEquals(Money.czk(25), updated.reward());
        assertFalse(updated.requiresApproval());
        assertFalse(updated.active());

        AuditEvent event = onlyAudit();
        assertEquals(AuditAction.TASK_UPDATED, event.action());
        assertTrue(event.details().contains("Odměna: 10 Kč → 25 Kč"));
        assertTrue(event.details().contains("Schvalování: ano → ne"));
    }

    @Test
    void anUnchangedTaskProducesNoAuditEvent() {
        Task existing = tasks.listAll().get(0);

        settings.saveTask(parent, existing.id(), existing.name(), existing.description(),
                existing.reward(), existing.requiresApproval(), existing.active());

        assertTrue(store.auditEvents().isEmpty());
    }

    @Test
    void invalidTaskInputIsRejected() {
        assertThrows(InvalidSettingsException.class,
                () -> settings.saveTask(parent, null, " ", "", Money.czk(10), true, true));
        assertThrows(InvalidSettingsException.class,
                () -> settings.saveTask(parent, null, "Název", "", Money.czk(-1), true, true));
        assertThrows(InvalidSettingsException.class,
                () -> settings.saveTask(parent, "missing", "Název", "", Money.czk(10), true, true));
        assertTrue(store.auditEvents().isEmpty());
    }

    private AuditEvent onlyAudit() {
        List<AuditEvent> audits = store.auditEvents();
        assertEquals(1, audits.size(), "exactly one audit event expected");
        return audits.get(0);
    }

    private Member member(String cognitoSub) {
        return members.findByCognitoSub(cognitoSub).orElseThrow();
    }
}

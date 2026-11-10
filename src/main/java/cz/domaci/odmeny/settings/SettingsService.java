package cz.domaci.odmeny.settings;

import cz.domaci.odmeny.audit.AuditAction;
import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.audit.AuditService;
import cz.domaci.odmeny.household.Household;
import cz.domaci.odmeny.household.HouseholdRepository;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberRepository;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.MoneyText;
import cz.domaci.odmeny.task.Task;
import cz.domaci.odmeny.task.TaskRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parent-only settings: household name, member names and base allowances, and
 * the chore catalog.
 *
 * <p>Every change is written with an audit event in the same transaction, so
 * the audit trail stays consistent with the settings it describes. Money
 * history is untouched: changing a reward only affects future completions,
 * because completions snapshot the reward.
 */
@ApplicationScoped
public class SettingsService {

    private static final int MAX_MEMBER_NAME = 60;
    private static final int MAX_TASK_NAME = 80;
    private static final int MAX_DESCRIPTION = 200;
    private static final Money MAX_AMOUNT = Money.czk(100_000);

    private final MemberRepository members;
    private final TaskRepository tasks;
    private final HouseholdRepository households;
    private final HouseholdService household;
    private final AuditService audits;

    @Inject
    public SettingsService(MemberRepository members, TaskRepository tasks, HouseholdRepository households,
            HouseholdService household, AuditService audits) {
        this.members = members;
        this.tasks = tasks;
        this.households = households;
        this.household = household;
        this.audits = audits;
    }

    public Household renameHousehold(Member actor, String name) {
        Household current = household.current();
        String clean = requireText(name, "Název domácnosti", MAX_MEMBER_NAME);
        if (clean.equals(current.name())) {
            return current;
        }

        Household updated = new Household(current.id(), clean, current.currency(), current.timezone());
        households.update(updated, audit(actor, null, null, AuditAction.HOUSEHOLD_RENAMED,
                "Název: " + current.name() + " → " + clean));
        return updated;
    }

    /**
     * Renames a member and, for a child, updates the base allowance and active
     * flag. Parents keep their base allowance and active flag.
     */
    public Member updateMember(Member actor, String memberId, String displayName, Money baseAllowance,
            boolean active) {
        Member target = members.findMember(household.id(), memberId)
                .orElseThrow(() -> new InvalidSettingsException("Člen nebyl nalezen."));

        String name = requireText(displayName, "Jméno", MAX_MEMBER_NAME);
        boolean child = !target.isParent();
        Money base = child ? requireAmount(baseAllowance, "Základní odměna") : target.baseAllowance();
        boolean newActive = child ? active : target.active();

        if (name.equals(target.displayName())
                && base.equals(target.baseAllowance())
                && newActive == target.active()) {
            return target;
        }

        Member updated = new Member(
                target.id(),
                target.householdId(),
                target.cognitoSub(),
                name,
                target.role(),
                base,
                newActive);

        List<String> changes = new ArrayList<>();
        if (!name.equals(target.displayName())) {
            changes.add("Jméno: " + target.displayName() + " → " + name);
        }
        if (!base.equals(target.baseAllowance())) {
            changes.add("Základ: " + MoneyText.format(target.baseAllowance())
                    + " → " + MoneyText.format(base));
        }
        if (newActive != target.active()) {
            changes.add("Aktivní: " + yesNo(target.active()) + " → " + yesNo(newActive));
        }

        boolean onlyRenamed = base.equals(target.baseAllowance()) && newActive == target.active();
        members.update(updated, audit(actor, updated.id(), updated.displayName(),
                onlyRenamed ? AuditAction.MEMBER_RENAMED : AuditAction.MEMBER_UPDATED,
                String.join(" · ", changes)));
        return updated;
    }

    /**
     * Creates a task when {@code taskId} is blank, otherwise updates the
     * existing task. The id and sort order are stable once created.
     */
    public Task saveTask(Member actor, String taskId, String name, String description, Money reward,
            boolean requiresApproval, boolean active) {
        String cleanName = requireText(name, "Název práce", MAX_TASK_NAME);
        String cleanDescription = optionalText(description, MAX_DESCRIPTION);
        Money amount = requireAmount(reward, "Odměna");

        if (taskId == null || taskId.isBlank()) {
            int nextOrder = tasks.listTasks(household.id()).stream()
                    .mapToInt(Task::sortOrder)
                    .max()
                    .orElse(0) + 1;
            Task created = new Task(UUID.randomUUID().toString(), household.id(), cleanName,
                    cleanDescription, amount, requiresApproval, active, nextOrder);
            tasks.create(created, audit(actor, null, null, AuditAction.TASK_CREATED,
                    describeTask(created)));
            return created;
        }

        Task existing = tasks.findById(household.id(), taskId)
                .orElseThrow(() -> new InvalidSettingsException("Práce nebyla nalezena."));
        Task updated = new Task(existing.id(), existing.householdId(), cleanName, cleanDescription,
                amount, requiresApproval, active, existing.sortOrder());
        if (updated.equals(existing)) {
            return existing;
        }

        tasks.update(updated, audit(actor, null, null, AuditAction.TASK_UPDATED,
                describeChanges(existing, updated)));
        return updated;
    }

    private AuditEvent audit(Member actor, String memberId, String memberName, AuditAction action,
            String details) {
        return audits.create(actor, memberId, memberName, household.currentPeriod(), action, details);
    }

    private static String describeTask(Task task) {
        return task.name() + " · " + MoneyText.format(task.reward())
                + (task.requiresApproval() ? " · schvaluje rodič" : " · bez schvalování");
    }

    private static String describeChanges(Task before, Task after) {
        List<String> changes = new ArrayList<>();
        if (!before.name().equals(after.name())) {
            changes.add("Název: " + before.name() + " → " + after.name());
        }
        if (!before.description().equals(after.description())) {
            changes.add("Popis změněn");
        }
        if (!before.reward().equals(after.reward())) {
            changes.add("Odměna: " + MoneyText.format(before.reward())
                    + " → " + MoneyText.format(after.reward()));
        }
        if (before.requiresApproval() != after.requiresApproval()) {
            changes.add("Schvalování: " + yesNo(before.requiresApproval())
                    + " → " + yesNo(after.requiresApproval()));
        }
        if (before.active() != after.active()) {
            changes.add("Aktivní: " + yesNo(before.active()) + " → " + yesNo(after.active()));
        }
        return changes.isEmpty() ? after.name() : String.join(" · ", changes);
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null) {
            throw new InvalidSettingsException(label + " je povinný údaj.");
        }
        String clean = value.strip();
        if (clean.isEmpty()) {
            throw new InvalidSettingsException(label + " nesmí být prázdný.");
        }
        if (clean.length() > maxLength) {
            throw new InvalidSettingsException(label + " je příliš dlouhý (max " + maxLength + ").");
        }
        return clean;
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String clean = value.strip();
        if (clean.length() > maxLength) {
            throw new InvalidSettingsException("Popis je příliš dlouhý (max " + maxLength + ").");
        }
        return clean;
    }

    private static Money requireAmount(Money amount, String label) {
        if (amount == null || amount.isNegative()) {
            throw new InvalidSettingsException(label + " nesmí být záporná.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidSettingsException(label + " je příliš vysoká.");
        }
        return amount;
    }

    private static String yesNo(boolean value) {
        return value ? "ano" : "ne";
    }
}

package cz.domaci.odmeny.web;

import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.PendingCompletion;
import cz.domaci.odmeny.household.Household;
import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.statement.Statement;
import cz.domaci.odmeny.task.Task;

import java.util.List;

/**
 * A complete, parent-only snapshot of household data for backup.
 */
public record ExportData(
        Household household,
        List<Member> members,
        List<Task> tasks,
        List<Completion> completions,
        List<LedgerEntry> ledger,
        List<Statement> statements,
        List<PendingCompletion> pending,
        List<AuditEvent> audit) {
}

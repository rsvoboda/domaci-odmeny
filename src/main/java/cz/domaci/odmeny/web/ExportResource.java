package cz.domaci.odmeny.web;

import cz.domaci.odmeny.audit.AuditEvent;
import cz.domaci.odmeny.audit.AuditService;
import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionRepository;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.ledger.LedgerRepository;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.statement.Statement;
import cz.domaci.odmeny.statement.StatementRepository;
import cz.domaci.odmeny.task.TaskService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parent-only export page. JSON is the full backup; the CSV files are for
 * spreadsheets and inspection.
 */
@Path("/export")
@ApplicationScoped
@RolesAllowed("parents")
public class ExportResource {

    private static final Logger LOG = Logger.getLogger(ExportResource.class);

    private final PageSupport pages;
    private final MemberService members;
    private final TaskService tasks;
    private final CompletionRepository completions;
    private final LedgerRepository ledger;
    private final StatementRepository statements;
    private final HouseholdService household;
    private final AuditService audits;

    @Inject
    @Location("export.html")
    Template exportPage;

    @Inject
    public ExportResource(PageSupport pages, MemberService members, TaskService tasks,
            CompletionRepository completions, LedgerRepository ledger, StatementRepository statements,
            HouseholdService household, AuditService audits) {
        this.pages = pages;
        this.members = members;
        this.tasks = tasks;
        this.completions = completions;
        this.ledger = ledger;
        this.statements = statements;
        this.household = household;
        this.audits = audits;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@QueryParam("flash") String flash) {
        Map<String, Object> data = pages.base(flash);
        try {
            List<Member> allMembers = members.listHouseholdMembers();
            List<Completion> allCompletions = collectCompletions(allMembers);
            List<LedgerEntry> allEntries = collectEntries(allMembers);
            List<Statement> allStatements = collectStatements(allMembers);

            data.put("memberCount", allMembers.size());
            data.put("taskCount", tasks.listAll().size());
            data.put("completionCount", allCompletions.size());
            data.put("ledgerCount", allEntries.size());
            data.put("statementCount", allStatements.size());
            data.put("pendingCount", completions.listPending().size());
            data.put("auditCount",
                    audits.listAll(earliestPeriod(allCompletions, allEntries, allStatements)).size());
            data.put("storageUnavailable", false);
        } catch (SdkException e) {
            LOG.warnf("Could not read export counts: %s", e.getMessage());
            data.put("storageUnavailable", true);
        }
        return exportPage.data(data);
    }

    @GET
    @Path("/data.json")
    @Produces(MediaType.APPLICATION_JSON)
    public ExportData data() {
        List<Member> allMembers = members.listHouseholdMembers();
        List<Completion> allCompletions = collectCompletions(allMembers);
        List<LedgerEntry> allEntries = collectEntries(allMembers);
        List<Statement> allStatements = collectStatements(allMembers);

        allCompletions.sort(Comparator.comparing(Completion::completedAt));
        allEntries.sort(Comparator.comparing(LedgerEntry::createdAt));
        allStatements.sort(Comparator.comparing(Statement::period));

        return new ExportData(
                household.current(),
                allMembers,
                tasks.listAll(),
                allCompletions,
                allEntries,
                allStatements,
                completions.listPending(),
                audits.listAll(earliestPeriod(allCompletions, allEntries, allStatements)));
    }

    @GET
    @Path("/ledger.csv")
    @Produces("text/csv; charset=UTF-8")
    public Response ledgerCsv() {
        List<Member> allMembers = members.listHouseholdMembers();
        Map<String, String> names = names(allMembers);

        StringBuilder csv = new StringBuilder();
        csv.append("member_id,member_name,period,type,amount_minor,description,"
                + "reference_id,created_by,created_at\n");

        for (Member member : allMembers) {
            List<LedgerEntry> entries = new ArrayList<>(ledger.listEntries(member.id()));
            entries.sort(Comparator.comparing(LedgerEntry::period)
                    .thenComparing(LedgerEntry::createdAt));
            for (LedgerEntry entry : entries) {
                csv.append(csv(entry.memberId())).append(',')
                        .append(csv(names.get(entry.memberId()))).append(',')
                        .append(csv(entry.period().value())).append(',')
                        .append(csv(entry.type().name())).append(',')
                        .append(entry.amount().minor()).append(',')
                        .append(csv(entry.description())).append(',')
                        .append(csv(entry.referenceId())).append(',')
                        .append(csv(entry.createdBy())).append(',')
                        .append(csv(entry.createdAt().toString())).append('\n');
            }
        }

        return download(csv.toString(), "ledger.csv");
    }

    @GET
    @Path("/statements.csv")
    @Produces("text/csv; charset=UTF-8")
    public Response statementsCsv() {
        List<Member> allMembers = members.listHouseholdMembers();
        Map<String, String> names = names(allMembers);

        StringBuilder csv = new StringBuilder();
        csv.append("member_id,member_name,period,status,base_minor,task_bonus_minor,"
                + "adjustments_minor,total_minor,paid_minor,balance_minor,"
                + "closed_at,paid_at,reopen_reason\n");

        List<Statement> all = new ArrayList<>();
        for (Member member : allMembers) {
            all.addAll(statements.listStatements(member.id()));
        }
        all.sort(Comparator.comparing(Statement::period).thenComparing(Statement::memberId));

        for (Statement statement : all) {
            csv.append(csv(statement.memberId())).append(',')
                    .append(csv(names.get(statement.memberId()))).append(',')
                    .append(csv(statement.period().value())).append(',')
                    .append(csv(statement.status().name())).append(',')
                    .append(statement.baseAllowance().minor()).append(',')
                    .append(statement.taskBonus().minor()).append(',')
                    .append(statement.adjustments().minor()).append(',')
                    .append(statement.total().minor()).append(',')
                    .append(statement.paid().minor()).append(',')
                    .append(statement.balance().minor()).append(',')
                    .append(csv(statement.closedAt() == null ? "" : statement.closedAt().toString())).append(',')
                    .append(csv(statement.paidAt() == null ? "" : statement.paidAt().toString())).append(',')
                    .append(csv(statement.reopenReason())).append('\n');
        }

        return download(csv.toString(), "statements.csv");
    }

    @GET
    @Path("/audit.csv")
    @Produces("text/csv; charset=UTF-8")
    public Response auditCsv() {
        List<Member> allMembers = members.listHouseholdMembers();
        List<Completion> allCompletions = collectCompletions(allMembers);
        List<LedgerEntry> allEntries = collectEntries(allMembers);
        List<Statement> allStatements = collectStatements(allMembers);

        StringBuilder csv = new StringBuilder();
        csv.append("occurred_at,period,actor_id,actor_name,action,member_id,member_name,details\n");

        for (AuditEvent event : audits.listAll(earliestPeriod(allCompletions, allEntries, allStatements))) {
            csv.append(csv(event.occurredAt().toString())).append(',')
                    .append(csv(event.period().value())).append(',')
                    .append(csv(event.actorId())).append(',')
                    .append(csv(event.actorName())).append(',')
                    .append(csv(event.action().name())).append(',')
                    .append(csv(event.memberId())).append(',')
                    .append(csv(event.memberName())).append(',')
                    .append(csv(event.details())).append('\n');
        }

        return download(csv.toString(), "audit.csv");
    }

    private List<Completion> collectCompletions(List<Member> allMembers) {
        List<Completion> all = new ArrayList<>();
        for (Member member : allMembers) {
            all.addAll(completions.listCompletions(member.id()));
        }
        return all;
    }

    private List<LedgerEntry> collectEntries(List<Member> allMembers) {
        List<LedgerEntry> all = new ArrayList<>();
        for (Member member : allMembers) {
            all.addAll(ledger.listEntries(member.id()));
        }
        return all;
    }

    private List<Statement> collectStatements(List<Member> allMembers) {
        List<Statement> all = new ArrayList<>();
        for (Member member : allMembers) {
            all.addAll(statements.listStatements(member.id()));
        }
        return all;
    }

    /**
     * The audit trail only needs to cover months that contain household data.
     */
    private Period earliestPeriod(List<Completion> allCompletions, List<LedgerEntry> allEntries,
            List<Statement> allStatements) {
        List<Period> periods = new ArrayList<>();
        allCompletions.forEach(completion -> periods.add(completion.period()));
        allEntries.forEach(entry -> periods.add(entry.period()));
        allStatements.forEach(statement -> periods.add(statement.period()));
        return periods.stream().min(Comparator.naturalOrder()).orElseGet(household::currentPeriod);
    }

    private static Map<String, String> names(List<Member> members) {
        Map<String, String> names = new HashMap<>();
        for (Member member : members) {
            names.put(member.id(), member.displayName());
        }
        return names;
    }

    private static Response download(String body, String filename) {
        return Response.ok(body)
                .type("text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        String escaped = value.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }
}

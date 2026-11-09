package cz.domaci.odmeny.web;

import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionRepository;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.ledger.LedgerEntry;
import cz.domaci.odmeny.ledger.LedgerRepository;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
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

    @Inject
    @Location("export.html")
    Template exportPage;

    @Inject
    public ExportResource(PageSupport pages, MemberService members, TaskService tasks,
            CompletionRepository completions, LedgerRepository ledger, StatementRepository statements,
            HouseholdService household) {
        this.pages = pages;
        this.members = members;
        this.tasks = tasks;
        this.completions = completions;
        this.ledger = ledger;
        this.statements = statements;
        this.household = household;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@QueryParam("flash") String flash) {
        Map<String, Object> data = pages.base(flash);
        try {
            List<Member> allMembers = members.listHouseholdMembers();
            data.put("memberCount", allMembers.size());
            data.put("taskCount", tasks.listAll().size());
            data.put("completionCount", allMembers.stream()
                    .mapToInt(member -> completions.listCompletions(member.id()).size()).sum());
            data.put("ledgerCount", allMembers.stream()
                    .mapToInt(member -> ledger.listEntries(member.id()).size()).sum());
            data.put("statementCount", allMembers.stream()
                    .mapToInt(member -> statements.listStatements(member.id()).size()).sum());
            data.put("pendingCount", completions.listPending().size());
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
        List<Completion> allCompletions = new ArrayList<>();
        List<LedgerEntry> allEntries = new ArrayList<>();
        List<Statement> allStatements = new ArrayList<>();

        for (Member member : allMembers) {
            allCompletions.addAll(completions.listCompletions(member.id()));
            allEntries.addAll(ledger.listEntries(member.id()));
            allStatements.addAll(statements.listStatements(member.id()));
        }

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
                completions.listPending());
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

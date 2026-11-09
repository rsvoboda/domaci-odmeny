package cz.domaci.odmeny.web;

import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.statement.InvalidStatementException;
import cz.domaci.odmeny.statement.NothingToPayException;
import cz.domaci.odmeny.statement.PendingCompletionsException;
import cz.domaci.odmeny.statement.StatementClosedException;
import cz.domaci.odmeny.statement.StatementConflictException;
import cz.domaci.odmeny.statement.StatementDetails;
import cz.domaci.odmeny.statement.StatementService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkException;

import java.net.URI;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Monthly statements, corrections, closing and payouts.
 *
 * <p>Parents can see every child. A child can only see their own statement;
 * the ownership check is server-side and independent of the navigation.
 */
@Path("/statements")
@ApplicationScoped
public class StatementResource {

    private static final Logger LOG = Logger.getLogger(StatementResource.class);

    private final CurrentUser currentUser;
    private final PageSupport pages;
    private final StatementService statements;
    private final MemberService members;
    private final HouseholdService household;

    @Inject
    @Location("statements.html")
    Template statementsPage;

    @Inject
    @Location("statement-detail.html")
    Template statementDetail;

    @Inject
    @Location("profile-missing.html")
    Template profileMissing;

    @Inject
    public StatementResource(CurrentUser currentUser, PageSupport pages, StatementService statements,
            MemberService members, HouseholdService household) {
        this.currentUser = currentUser;
        this.pages = pages;
        this.statements = statements;
        this.members = members;
        this.household = household;
    }

    @GET
    @RolesAllowed("parents")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance overview(@QueryParam("flash") String flash) {
        Map<String, Object> data = pages.base(flash);
        try {
            ZoneId zone = household.zone();
            Period current = household.currentPeriod();
            Period previous = current.previous();
            List<Member> children = children();

            data.put("rows", children.stream()
                    .map(member -> Views.overviewRow(member,
                            statements.statement(member, current),
                            statements.statement(member, previous),
                            zone))
                    .toList());
            data.put("stored", children.stream()
                    .flatMap(member -> statements.storedStatements(member).stream()
                            .filter(statement -> !statement.period().equals(current)
                                    && !statement.period().equals(previous))
                            .map(statement -> Views.statement(statement, member.displayName(), zone)))
                    .sorted(Comparator.comparing(Views.StatementView::periodValue).reversed())
                    .toList());
            data.put("storageUnavailable", false);
        } catch (SdkException e) {
            LOG.warnf("Could not read statements: %s", e.getMessage());
            data.put("rows", List.of());
            data.put("stored", List.of());
            data.put("storageUnavailable", true);
        }
        return statementsPage.data(data);
    }

    /**
     * The child's own current-month statement.
     */
    @GET
    @Path("/mine")
    @RolesAllowed("children")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance mine(@QueryParam("flash") String flash) {
        Map<String, Object> data = pages.base(flash);
        Optional<Member> member = currentUser.member();
        if (member.isEmpty()) {
            data.put("storageUnavailable", false);
            return profileMissing.data(data);
        }
        return detailView(data, member.get(), household.currentPeriod());
    }

    @GET
    @Path("/{memberId}/{period}")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance detail(@PathParam("memberId") String memberId,
            @PathParam("period") String periodText,
            @QueryParam("flash") String flash) {
        Member target = findMember(memberId);
        if (!currentUser.isParent()) {
            Member viewer = currentUser.member().orElse(null);
            if (viewer == null || !viewer.id().equals(target.id())) {
                throw new ForbiddenException();
            }
        }
        Period period = parsePeriod(periodText);
        try {
            return detailView(pages.base(flash), target, period);
        } catch (InvalidStatementException e) {
            throw new NotFoundException();
        }
    }

    @POST
    @Path("/{memberId}/{period}/corrections")
    @RolesAllowed("parents")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response addCorrection(@PathParam("memberId") String memberId,
            @PathParam("period") String periodText,
            @FormParam("amount") String amountText,
            @FormParam("reason") String reason) {
        Member target = findMember(memberId);
        Period period = parsePeriod(periodText);
        try {
            Member parent = currentUser.member().orElseThrow(ForbiddenException::new);
            statements.addAdjustment(parent, target, period, MoneyParser.parse(amountText), reason);
            return redirect(memberId, period, "adjusted");
        } catch (IllegalArgumentException e) {
            return redirect(memberId, period, "invalid");
        } catch (StatementClosedException e) {
            return redirect(memberId, period, "closed-month");
        } catch (InvalidStatementException e) {
            return redirect(memberId, period, "invalid");
        } catch (SdkException e) {
            LOG.warnf("Could not add a correction: %s", e.getMessage());
            return redirect(memberId, period, "storage");
        }
    }

    @POST
    @Path("/{memberId}/{period}/close")
    @RolesAllowed("parents")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response close(@PathParam("memberId") String memberId, @PathParam("period") String periodText) {
        Member target = findMember(memberId);
        Period period = parsePeriod(periodText);
        try {
            Member parent = currentUser.member().orElseThrow(ForbiddenException::new);
            statements.close(parent, target, period);
            return redirect(memberId, period, "closed");
        } catch (PendingCompletionsException e) {
            return redirect(memberId, period, "pending");
        } catch (StatementConflictException e) {
            return redirect(memberId, period, "conflict");
        } catch (InvalidStatementException e) {
            return redirect(memberId, period, "invalid");
        } catch (SdkException e) {
            LOG.warnf("Could not close the month: %s", e.getMessage());
            return redirect(memberId, period, "storage");
        }
    }

    @POST
    @Path("/{memberId}/{period}/paid")
    @RolesAllowed("parents")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response markPaid(@PathParam("memberId") String memberId, @PathParam("period") String periodText) {
        Member target = findMember(memberId);
        Period period = parsePeriod(periodText);
        try {
            Member parent = currentUser.member().orElseThrow(ForbiddenException::new);
            statements.markPaid(parent, target, period);
            return redirect(memberId, period, "paid");
        } catch (NothingToPayException e) {
            return redirect(memberId, period, "nothing-to-pay");
        } catch (StatementConflictException e) {
            return redirect(memberId, period, "conflict");
        } catch (InvalidStatementException e) {
            return redirect(memberId, period, "invalid");
        } catch (SdkException e) {
            LOG.warnf("Could not record the payout: %s", e.getMessage());
            return redirect(memberId, period, "storage");
        }
    }

    @POST
    @Path("/{memberId}/{period}/reopen")
    @RolesAllowed("parents")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response reopen(@PathParam("memberId") String memberId,
            @PathParam("period") String periodText,
            @FormParam("reason") String reason) {
        Member target = findMember(memberId);
        Period period = parsePeriod(periodText);
        try {
            Member parent = currentUser.member().orElseThrow(ForbiddenException::new);
            statements.reopen(parent, target, period, reason);
            return redirect(memberId, period, "reopened");
        } catch (StatementConflictException e) {
            return redirect(memberId, period, "conflict");
        } catch (InvalidStatementException e) {
            return redirect(memberId, period, "invalid");
        } catch (SdkException e) {
            LOG.warnf("Could not reopen the month: %s", e.getMessage());
            return redirect(memberId, period, "storage");
        }
    }

    private TemplateInstance detailView(Map<String, Object> data, Member target, Period period) {
        try {
            StatementDetails details = statements.details(target, period);
            data.put("detail", Views.statementDetail(details, target.displayName(), household.zone()));
            data.put("memberName", target.displayName());
            data.put("storageUnavailable", false);
        } catch (SdkException e) {
            LOG.warnf("Could not read the statement: %s", e.getMessage());
            data.put("memberName", target.displayName());
            data.put("storageUnavailable", true);
        }
        return statementDetail.data(data);
    }

    private Member findMember(String memberId) {
        return members.findById(memberId).orElseThrow(NotFoundException::new);
    }

    private List<Member> children() {
        return members.listHouseholdMembers().stream()
                .filter(member -> !member.isParent() && member.active())
                .toList();
    }

    private static Period parsePeriod(String text) {
        try {
            return Period.parse(text);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException();
        }
    }

    private static Response redirect(String memberId, Period period, String flash) {
        return Response.seeOther(URI.create(
                "/statements/" + memberId + "/" + period.value() + "?flash=" + flash)).build();
    }
}

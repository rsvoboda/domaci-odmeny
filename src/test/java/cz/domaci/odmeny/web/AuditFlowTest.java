package cz.domaci.odmeny.web;

import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionService;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.statement.StatementService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class AuditFlowTest extends AbstractWebTest {

    @Inject
    CompletionService completions;

    @Inject
    StatementService statements;

    @Inject
    MemberService members;

    @Inject
    HouseholdService household;

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentSeesTheAuditTrailForTheCurrentMonth() {
        Member child = member("dev-child-1");
        approveChore(child, "odpadky");
        statements.addAdjustment(member("dev-parent-1"), child, current(), Money.czk(20),
                "Bonus za pomoc");

        given()
                .when().get("/audit")
                .then()
                .statusCode(200)
                .body(containsString("Audit změn"))
                .body(containsString("Práce schválena"))
                .body(containsString("Úprava výpisu"))
                .body(containsString("Bonus za pomoc"))
                .body(containsString("Dítě 1"))
                .body(containsString("Provedl(a): Rodič 1"));
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void auditMonthPickerCanShowAPreviousMonth() {
        given()
                .when().get("/audit?period=" + current().previous().value())
                .then()
                .statusCode(200)
                .body(containsString("V tomto měsíci nejsou žádné zaznamenané změny"));

        given()
                .when().get("/audit?period=not-a-month")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childCannotOpenTheAuditTrail() {
        given().when().get("/audit").then().statusCode(403);
        given().when().get("/export/audit.csv").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void auditIsIncludedInJsonAndCsvExports() {
        Member child = member("dev-child-1");
        approveChore(child, "odpadky");

        given()
                .when().get("/export")
                .then()
                .statusCode(200)
                .body(containsString("Audit změn"))
                .body(containsString("Audit změn (CSV)"));

        given()
                .when().get("/export/data.json")
                .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .body(containsString("\"audit\""))
                .body(containsString("CHORE_APPROVED"))
                .body(containsString("Rodič 1"));

        given()
                .when().get("/export/audit.csv")
                .then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("audit.csv"))
                .body(containsString("occurred_at,period,actor_id,actor_name,action,member_id,member_name,details"))
                .body(containsString("CHORE_APPROVED"))
                .body(containsString("Dítě 1"));
    }

    private void approveChore(Member child, String taskId) {
        Completion pending = completions.logChore(child, taskId, null);
        completions.approve(member("dev-parent-1"), pending.id());
    }

    private Member member(String cognitoSub) {
        return members.findByCognitoSub(cognitoSub).orElseThrow();
    }

    private Period current() {
        return household.currentPeriod();
    }
}

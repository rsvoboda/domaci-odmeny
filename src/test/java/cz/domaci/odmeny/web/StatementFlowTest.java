package cz.domaci.odmeny.web;

import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionService;
import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.shared.Period;
import cz.domaci.odmeny.statement.StatementService;
import cz.domaci.odmeny.testsupport.InMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class StatementFlowTest extends AbstractWebTest {

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
    void parentSeesTheStatementsOverview() {
        given()
                .when().get("/statements")
                .then()
                .statusCode(200)
                .body(containsString("Výplaty"))
                .body(containsString("Dítě 1"))
                .body(containsString("Dítě 2"))
                .body(containsString("Otevřený"));
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childCannotOpenParentStatementPagesOrExports() {
        given().when().get("/statements").then().statusCode(403);
        given().when().get("/export").then().statusCode(403);
        given().when().get("/export/data.json").then().statusCode(403);
        given().when().get("/export/ledger.csv").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentAddsACorrectionAndSeesItOnTheStatement() {
        Member child = member("dev-child-1");
        approveChore(child, "odpadky");
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("amount", "20,50")
                .formParam("reason", "Bonus za pomoc")
                .redirects().follow(false)
                .when().post(detailUrl(child) + "/corrections")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=adjusted"));

        given()
                .when().get(detailUrl(child))
                .then()
                .statusCode(200)
                .body(containsString("80,50 Kč"))
                .body(containsString("Bonus za pomoc"))
                .body(containsString("Zapsat úpravu"));
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentClosesPaysAndReopensTheMonth() {
        Member child = member("dev-child-1");
        approveChore(child, "odpadky");
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .redirects().follow(false)
                .when().post(detailUrl(child) + "/close")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=closed"));

        given()
                .when().get(detailUrl(child))
                .then()
                .statusCode(200)
                .body(containsString("Uzavřený"))
                .body(not(containsString("Zapsat úpravu")));

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("amount", "10")
                .formParam("reason", "Pozdní úprava")
                .redirects().follow(false)
                .when().post(detailUrl(child) + "/corrections")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=closed-month"));

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .redirects().follow(false)
                .when().post(detailUrl(child) + "/paid")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=paid"));

        given()
                .when().get(detailUrl(child))
                .then()
                .statusCode(200)
                .body(containsString("Vyplacený"))
                .body(containsString("0 Kč"));

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("reason", "Chyba ve výpočtu")
                .redirects().follow(false)
                .when().post(detailUrl(child) + "/reopen")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=reopened"));

        given()
                .when().get(detailUrl(child))
                .then()
                .statusCode(200)
                .body(containsString("Otevřený"))
                .body(containsString("Chyba ve výpočtu"));
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void closingIsBlockedWhileCompletionsArePending() {
        Member child = member("dev-child-1");
        completions.logChore(child, "odpadky", null);
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .redirects().follow(false)
                .when().post(detailUrl(child) + "/close")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=pending"));
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childSeesOwnStatementButNotASiblings() {
        given()
                .when().get("/statements/mine")
                .then()
                .statusCode(200)
                .body(containsString("Souhrn"))
                .body(containsString("Dítě 1"));

        given()
                .when().get("/statements/" + InMemoryStore.CHILD_2 + "/" + current().value())
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentExportsJsonAndCsv() {
        Member child = member("dev-child-1");
        approveChore(child, "odpadky");
        statements.close(member("dev-parent-1"), child, current());

        given()
                .when().get("/export")
                .then()
                .statusCode(200)
                .body(containsString("Export dat"))
                .body(containsString("Kompletní data domácnosti"));

        given()
                .when().get("/export/data.json")
                .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .body(containsString("\"household\""))
                .body(containsString("child-1"))
                .body(containsString("TASK_REWARD"));

        given()
                .when().get("/export/ledger.csv")
                .then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("ledger.csv"))
                .body(containsString("amount_minor"))
                .body(containsString("TASK_REWARD"));

        given()
                .when().get("/export/statements.csv")
                .then()
                .statusCode(200)
                .body(containsString("balance_minor"))
                .body(containsString("CLOSED"));
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void statementPostsRequireCsrf() {
        Member child = member("dev-child-1");

        given()
                .formParam("amount", "10")
                .formParam("reason", "Bez tokenu")
                .redirects().follow(false)
                .when().post(detailUrl(child) + "/corrections")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void czechCharactersSurviveBrowserStyleFormPosts() throws Exception {
        Member child = member("dev-child-1");
        String token = csrfToken();
        String reason = "Příliš žluťoučký kůň – úprava";
        String body = "csrf-token=" + encode(token)
                + "&amount=" + encode("-10,50")
                + "&reason=" + encode(reason);

        given()
                .cookie("csrftoken", token)
                // Browsers submit UTF-8 form data without an explicit charset.
                .contentType("application/x-www-form-urlencoded")
                .body(body)
                .redirects().follow(false)
                .when().post(detailUrl(child) + "/corrections")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=adjusted"));

        given()
                .when().get(detailUrl(child))
                .then()
                .statusCode(200)
                .body(containsString(reason));
    }

    private static String encode(String value) throws Exception {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
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

    private String detailUrl(Member member) {
        return "/statements/" + member.id() + "/" + current().value();
    }
}

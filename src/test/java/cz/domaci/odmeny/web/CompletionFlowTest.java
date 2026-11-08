package cz.domaci.odmeny.web;

import cz.domaci.odmeny.completion.Completion;
import cz.domaci.odmeny.completion.CompletionService;
import cz.domaci.odmeny.completion.CompletionStatus;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.task.Task;
import cz.domaci.odmeny.testsupport.InMemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CompletionFlowTest extends AbstractWebTest {

    @Inject
    CompletionService completions;

    @Inject
    MemberService members;

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childLogsAChoreAndSeesItPending() {
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("taskId", "odpadky")
                .formParam("note", "vynesl jsem to")
                .redirects().follow(false)
                .when().post("/completions")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=logged"));

        assertEquals(1, store.pendingItems().size());
        String completionId = store.pendingItems().get(0).completionId();
        Completion stored = store.completion(InMemoryStore.CHILD_1, completionId).orElseThrow();
        assertEquals(CompletionStatus.PENDING, stored.status());

        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("Čeká na schválení"))
                .body(containsString("vynesl jsem to"));

        given()
                .when().get("/history")
                .then()
                .statusCode(200)
                .body(containsString("Odpadky"))
                .body(containsString("vynesl jsem to"));
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void postWithoutCsrfTokenIsRejected() {
        given()
                .formParam("taskId", "odpadky")
                .redirects().follow(false)
                .when().post("/completions")
                .then().statusCode(400);

        assertTrue(store.pendingItems().isEmpty());
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentApprovesAPendingChore() {
        Member child = member("dev-child-1");
        Completion pending = completions.logChore(child, "odpadky", "vynesl jsem to");
        String token = csrfToken();

        given()
                .when().get("/approvals")
                .then()
                .statusCode(200)
                .body(containsString("Dítě 1"))
                .body(containsString("Odpadky"))
                .body(containsString("10 Kč"))
                .body(containsString("vynesl jsem to"));

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .redirects().follow(false)
                .when().post("/approvals/" + pending.id() + "/approve")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=approved"));

        assertTrue(store.pendingItems().isEmpty());
        assertEquals(1, store.ledger(child.id(), pending.period()).size());

        given()
                .when().get("/approvals")
                .then()
                .statusCode(200)
                .body(containsString("Fronta je prázdná"));
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentRejectsAPendingChoreWithAReason() {
        Member child = member("dev-child-1");
        Completion pending = completions.logChore(child, "odpadky", null);
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("reason", "Nebylo to hotové")
                .redirects().follow(false)
                .when().post("/approvals/" + pending.id() + "/reject")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=rejected"));

        Completion rejected = store.completion(child.id(), pending.id()).orElseThrow();
        assertEquals(CompletionStatus.REJECTED, rejected.status());
        assertEquals("Nebylo to hotové", rejected.rejectionReason());
        assertTrue(store.pendingItems().isEmpty());
        assertTrue(store.ledger(child.id(), rejected.period()).isEmpty());
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childSeesTheRejectionReason() {
        Member child = member("dev-child-1");
        Member parent = member("dev-parent-1");
        Completion pending = completions.logChore(child, "odpadky", null);
        completions.reject(parent, pending.id(), "Nebylo to hotové");

        given()
                .when().get("/history")
                .then()
                .statusCode(200)
                .body(containsString("Zamítnuto"))
                .body(containsString("Nebylo to hotové"));
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void approvingTwiceDoesNotCreditTwice() {
        Member child = member("dev-child-1");
        Completion pending = completions.logChore(child, "odpadky", null);
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .redirects().follow(false)
                .when().post("/approvals/" + pending.id() + "/approve")
                .then().statusCode(303);

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .redirects().follow(false)
                .when().post("/approvals/" + pending.id() + "/approve")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=conflict"));

        assertEquals(1, store.ledger(child.id(), pending.period()).size());
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childCannotApprove() {
        Member child = member("dev-child-1");
        Completion pending = completions.logChore(child, "odpadky", null);
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .redirects().follow(false)
                .when().post("/approvals/" + pending.id() + "/approve")
                .then().statusCode(403);

        assertTrue(store.ledger(child.id(), pending.period()).isEmpty());
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childCannotSeeSiblingActivity() {
        Member sibling = member("dev-child-2");
        completions.logChore(sibling, "odpadky", "sibling-secret");

        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(not(containsString("sibling-secret")));

        given()
                .when().get("/history")
                .then()
                .statusCode(200)
                .body(not(containsString("sibling-secret")));
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void autoApprovedChoreIsCreditedImmediately() {
        store.putTask(new Task("auto", InMemoryStore.HOUSEHOLD_ID, "Automatická práce", "",
                Money.czk(5), false, true, 10));
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("taskId", "auto")
                .redirects().follow(false)
                .when().post("/completions")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=logged"));

        assertTrue(store.pendingItems().isEmpty());
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("55 Kč"));
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void unknownTaskShowsAnError() {
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("taskId", "unknown")
                .redirects().follow(false)
                .when().post("/completions")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=task"));

        assertTrue(store.pendingItems().isEmpty());
    }

    private Member member(String cognitoSub) {
        return members.findByCognitoSub(cognitoSub).orElseThrow();
    }
}

package cz.domaci.odmeny.web;

import cz.domaci.odmeny.household.HouseholdService;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberService;
import cz.domaci.odmeny.shared.Money;
import cz.domaci.odmeny.task.TaskService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SettingsFlowTest extends AbstractWebTest {

    @Inject
    MemberService members;

    @Inject
    TaskService tasks;

    @Inject
    HouseholdService household;

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentSeesHouseholdMembersAndTheChoreCatalog() {
        given()
                .when().get("/settings")
                .then()
                .statusCode(200)
                .body(containsString("Nastavení"))
                .body(containsString("Naše domácnost"))
                .body(containsString("Dítě 1"))
                .body(containsString("Rodič 1"))
                .body(containsString("Odpadky"))
                .body(containsString("Nová práce"));
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childCannotOpenSettings() {
        given().when().get("/settings").then().statusCode(403);

        String token = csrfToken();
        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("name", "Hack")
                .redirects().follow(false)
                .when().post("/settings/household")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentCanRenameTheHousehold() {
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("name", "U Nováků")
                .redirects().follow(false)
                .when().post("/settings/household")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=household-saved"));

        assertEquals("U Nováků", household.name());

        given()
                .when().get("/settings?flash=household-saved")
                .then()
                .statusCode(200)
                .body(containsString("Název domácnosti byl uložen."))
                .body(containsString("U Nováků"));

        given()
                .when().get("/audit")
                .then()
                .statusCode(200)
                .body(containsString("Domácnost přejmenována"))
                .body(containsString("Domácnost"));
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentCanRenameAChildAndChangeTheAllowance() {
        String token = csrfToken();
        Member child = member("dev-child-1");

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("displayName", "Anička")
                .formParam("baseAllowance", "80,50")
                .formParam("active", "on")
                .redirects().follow(false)
                .when().post("/settings/members/" + child.id())
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=member-saved"));

        Member updated = members.findById(child.id()).orElseThrow();
        assertEquals("Anička", updated.displayName());
        assertEquals(Money.czk(80).plus(Money.minor(50)), updated.baseAllowance());
        assertTrue(updated.active());

        given()
                .when().get("/settings")
                .then()
                .statusCode(200)
                .body(containsString("Anička"))
                .body(containsString("80,50"));
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentCanDeactivateAChild() {
        String token = csrfToken();
        Member child = member("dev-child-1");

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("displayName", child.displayName())
                .formParam("baseAllowance", "50")
                .redirects().follow(false)
                .when().post("/settings/members/" + child.id())
                .then()
                .statusCode(303);

        assertFalse(members.findById(child.id()).orElseThrow().active());
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentCanAddAndUpdateAChore() {
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("name", "Umytí nádobí")
                .formParam("description", "Kuchyň")
                .formParam("reward", "15")
                .formParam("requiresApproval", "on")
                .formParam("active", "on")
                .redirects().follow(false)
                .when().post("/settings/tasks")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=task-added"));

        var created = tasks.listAll().stream()
                .filter(task -> task.name().equals("Umytí nádobí"))
                .findFirst()
                .orElseThrow();
        assertEquals(Money.czk(15), created.reward());

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("taskId", created.id())
                .formParam("name", created.name())
                .formParam("description", created.description())
                .formParam("reward", "20")
                .formParam("active", "on")
                .redirects().follow(false)
                .when().post("/settings/tasks")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=task-saved"));

        var updated = tasks.listAll().stream()
                .filter(task -> task.id().equals(created.id()))
                .findFirst()
                .orElseThrow();
        assertEquals(Money.czk(20), updated.reward());
        assertFalse(updated.requiresApproval());
        assertTrue(updated.active());
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void invalidSettingsInputShowsAnErrorAndWritesNothing() {
        String token = csrfToken();

        given()
                .cookie("csrftoken", token)
                .formParam("csrf-token", token)
                .formParam("name", "   ")
                .redirects().follow(false)
                .when().post("/settings/household")
                .then()
                .statusCode(303)
                .header("Location", containsString("flash=settings-invalid"));

        given()
                .when().get("/settings?flash=settings-invalid")
                .then()
                .statusCode(200)
                .body(containsString("Zadané údaje nejsou platné"));

        assertEquals("Naše domácnost", household.name());
        assertTrue(store.auditEvents().isEmpty());
    }

    private Member member(String cognitoSub) {
        return members.findByCognitoSub(cognitoSub).orElseThrow();
    }
}

package cz.domaci.odmeny.web;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class HomePageTest extends AbstractWebTest {

    @Test
    void anonymousRequestIsRejected() {
        given()
                .redirects().follow(false)
                .when().get("/")
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childSeesChoresAndCurrentTotal() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("Co jsi udělal?"))
                .body(containsString("Odpadky"))
                .body(containsString("Myčka"))
                .body(containsString("Vysávání"))
                .body(containsString("Dítě 1"))
                .body(containsString("50 Kč"));
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void serviceWorkerPrecachesTheVersionedStylesheet() {
        String page = given()
                .when().get("/")
                .then().statusCode(200)
                .extract().asString();

        Matcher stylesheet = Pattern.compile("/assets/app\\.css\\?v=[0-9]+").matcher(page);
        assertTrue(stylesheet.find(), "the layout should link a versioned stylesheet");

        String serviceWorker = given()
                .when().get("/sw.js")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(serviceWorker.contains(stylesheet.group()),
                "sw.js should precache the same stylesheet URL: " + stylesheet.group());
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentSeesApprovalQueueAndChildTotals() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("Čeká na schválení"))
                .body(containsString("Děti tento měsíc"))
                .body(containsString("Dítě 1"))
                .body(containsString("Dítě 2"))
                .body(containsString("50 Kč"));
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childCannotOpenTheApprovalQueue() {
        given()
                .when().get("/approvals")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentCannotOpenChildHistory() {
        given()
                .when().get("/history")
                .then().statusCode(403);
    }
}

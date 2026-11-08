package cz.domaci.odmeny.web;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class HomePageTest {

    @Test
    void anonymousRequestIsRejected() {
        given()
                .redirects().follow(false)
                .when().get("/")
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "dev-child-1", roles = "children")
    void childSeesTheProtectedHomePage() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("Ahoj"))
                .body(containsString("Dítě"))
                .body(containsString("Slice 1"));
    }

    @Test
    @TestSecurity(user = "dev-parent-1", roles = "parents")
    void parentSeesTheProtectedHomePage() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("Rodič"));
    }
}

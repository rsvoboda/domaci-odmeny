package cz.domaci.odmeny.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class PublicEndpointsTest {

    @Test
    void anonymousCanLoadStaticAssets() {
        given()
                .redirects().follow(false)
                .when().get("/assets/htmx.min.js")
                .then().statusCode(200);

        given()
                .redirects().follow(false)
                .when().get("/assets/pico.min.css")
                .then().statusCode(200);
    }

    @Test
    void anonymousCanLoadTheFavicon() {
        given()
                .redirects().follow(false)
                .when().get("/favicon.svg")
                .then().statusCode(200);
    }

    @Test
    void anonymousCanLoadTheInstallableAppShell() {
        given()
                .redirects().follow(false)
                .when().get("/manifest.webmanifest")
                .then()
                .statusCode(200)
                .body(containsString("\"name\": \"Domácí odměny\""))
                .body(containsString("\"display\": \"standalone\""));

        given()
                .redirects().follow(false)
                .when().get("/sw.js")
                .then()
                .statusCode(200)
                .body(containsString("domaci-odmeny-v1"));

        given()
                .redirects().follow(false)
                .when().get("/icons/icon.svg")
                .then().statusCode(200);

        given()
                .redirects().follow(false)
                .when().get("/icons/icon-maskable.svg")
                .then().statusCode(200);
    }

    @Test
    void healthEndpointIsPublic() {
        given()
                .redirects().follow(false)
                .when().get("/q/health")
                .then()
                .statusCode(200)
                .body(containsString("\"status\": \"UP\""));
    }
}

package cz.domaci.odmeny.web;

import cz.domaci.odmeny.testsupport.InMemoryStore;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Base class for web tests: resets the in-memory repositories and fetches a
 * CSRF token for form posts.
 */
public abstract class AbstractWebTest {

    @Inject
    protected InMemoryStore store;

    @BeforeEach
    void resetStore() {
        store.reset();
    }

    protected String csrfToken() {
        String token = given()
                .redirects().follow(false)
                .when().get("/")
                .then()
                .statusCode(200)
                .extract().cookie("csrftoken");
        assertNotNull(token, "the GET response must set the CSRF cookie");
        return token;
    }
}

package cz.domaci.odmeny.web;

import cz.domaci.odmeny.testsupport.InMemoryStore;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Base class for web tests: resets the in-memory repositories and fetches a
 * CSRF token for form posts.
 */
public abstract class AbstractWebTest {

    static {
        // RestAssured defaults to ISO-8859-1 for form params, which mangles
        // Czech characters such as "č" or "ř". Browsers send UTF-8.
        RestAssured.config = RestAssured.config()
                .encoderConfig(EncoderConfig.encoderConfig()
                        .defaultContentCharset(StandardCharsets.UTF_8));
    }

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

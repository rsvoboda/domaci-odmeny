package cz.domaci.odmeny.infrastructure.dynamodb;

import cz.domaci.odmeny.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Duration;

/**
 * Creates the DynamoDB client. In AWS the default credential chain is used; a
 * local endpoint (DynamoDB Local) gets dummy credentials and short timeouts so
 * that a missing local database fails fast instead of hanging.
 */
@ApplicationScoped
public class DynamoDbProducer {

    private final AppConfig config;

    @Inject
    public DynamoDbProducer(AppConfig config) {
        this.config = config;
    }

    @Produces
    @Singleton
    public DynamoDbClient dynamoDbClient() {
        var builder = DynamoDbClient.builder()
                .region(Region.of(config.dynamodb().region()))
                .httpClientBuilder(UrlConnectionHttpClient.builder());

        config.dynamodb().endpointOverride().ifPresent(endpoint -> {
            builder.endpointOverride(URI.create(endpoint));
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("local", "local")));
            builder.overrideConfiguration(override -> override
                    .apiCallTimeout(Duration.ofSeconds(3))
                    .apiCallAttemptTimeout(Duration.ofSeconds(1))
                    .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()));
        });

        return builder.build();
    }

    public void close(@Disposes DynamoDbClient client) {
        client.close();
    }
}

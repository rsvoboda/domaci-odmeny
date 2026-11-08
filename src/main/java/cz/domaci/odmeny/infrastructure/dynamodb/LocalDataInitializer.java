package cz.domaci.odmeny.infrastructure.dynamodb;

import cz.domaci.odmeny.config.AppConfig;
import cz.domaci.odmeny.member.Member;
import cz.domaci.odmeny.member.MemberRepository;
import cz.domaci.odmeny.member.MemberRole;
import cz.domaci.odmeny.shared.Money;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Development-only bootstrap: creates the four local DynamoDB tables and seeds
 * the four household members used by the dev login page.
 *
 * <p>If DynamoDB Local is not running the application still starts; the UI
 * shows a "database unavailable" banner.
 */
@ApplicationScoped
public class LocalDataInitializer {

    private static final Logger LOG = Logger.getLogger(LocalDataInitializer.class);

    private final DynamoDbClient client;
    private final AppConfig config;
    private final MemberRepository members;

    @Inject
    public LocalDataInitializer(DynamoDbClient client, AppConfig config, MemberRepository members) {
        this.client = client;
        this.config = config;
        this.members = members;
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.dynamodb().createTables()) {
            return;
        }

        try {
            createTableIfMissing(config.dynamodb().tables().members());
            createTableIfMissing(config.dynamodb().tables().tasks());
            createTableIfMissing(config.dynamodb().tables().activity());
            createTableIfMissing(config.dynamodb().tables().pending());
            LOG.info("DynamoDB tables are ready");

            if (config.seed().enabled()) {
                seedMembers();
            }
        } catch (SdkException e) {
            LOG.warnf("DynamoDB is not reachable at %s. Start it with `podman compose up -d dynamodb`. Cause: %s",
                    config.dynamodb().endpointOverride().orElse("AWS"),
                    e.getMessage());
        }
    }

    private void createTableIfMissing(String tableName) {
        try {
            client.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
        } catch (ResourceNotFoundException e) {
            client.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("PK")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("SK")
                                    .attributeType(ScalarAttributeType.S)
                                    .build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("PK")
                                    .keyType(KeyType.HASH)
                                    .build(),
                            KeySchemaElement.builder()
                                    .attributeName("SK")
                                    .keyType(KeyType.RANGE)
                                    .build())
                    .provisionedThroughput(ProvisionedThroughput.builder()
                            .readCapacityUnits(5L)
                            .writeCapacityUnits(5L)
                            .build())
                    .build());
            LOG.infof("Created local DynamoDB table %s", tableName);
        }
    }

    private void seedMembers() {
        if (!members.listByHousehold(config.householdId()).isEmpty()) {
            return;
        }

        members.put(new Member("parent-1", config.householdId(), "dev-parent-1",
                "Rodič 1", MemberRole.PARENT, Money.ZERO, true));
        members.put(new Member("parent-2", config.householdId(), "dev-parent-2",
                "Rodič 2", MemberRole.PARENT, Money.ZERO, true));
        members.put(new Member("child-1", config.householdId(), "dev-child-1",
                "Dítě 1", MemberRole.CHILD, Money.czk(50), true));
        members.put(new Member("child-2", config.householdId(), "dev-child-2",
                "Dítě 2", MemberRole.CHILD, Money.czk(50), true));

        LOG.info("Seeded development household members");
    }
}

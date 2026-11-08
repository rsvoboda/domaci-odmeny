package cz.domaci.odmeny.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

/**
 * Application configuration rooted at {@code app.*}.
 */
@ConfigMapping(prefix = "app")
public interface AppConfig {

    @WithDefault("Domaci odmeny")
    String name();

    @WithDefault("default")
    String householdId();

    @WithDefault("Nase domacnost")
    String householdName();

    DynamoDb dynamodb();

    Seed seed();

    DevAuth devAuth();

    interface Seed {

        @WithDefault("false")
        boolean enabled();
    }

    interface DevAuth {

        @WithDefault("false")
        boolean enabled();
    }

    interface DynamoDb {

        @WithDefault("eu-central-1")
        String region();

        /**
         * When set, the DynamoDB client talks to a local endpoint (DynamoDB Local).
         */
        Optional<String> endpointOverride();

        Tables tables();

        /**
         * Create missing tables on startup. Development and test only.
         */
        @WithDefault("false")
        boolean createTables();
    }

    interface Tables {

        @WithDefault("Members")
        String members();

        @WithDefault("Tasks")
        String tasks();

        @WithDefault("Activity")
        String activity();

        @WithDefault("Pending")
        String pending();
    }
}

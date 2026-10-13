package com.cherrytest.shop;

import com.cherrytest.shop.domain.port.RepositoryException;
import com.cherrytest.shop.infrastructure.postgres.PostgresConnection;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@OpenAPIDefinition(
        info = @Info(
                title = "shop-java-api",
                version = "1.0.0",
                description = "Shop showcase API: catalog, cart and checkout"))
public class ShopApplication {
    private static final Logger LOG = LoggerFactory.getLogger(ShopApplication.class);
    private static final String DEFAULT_ADDRESS = "0.0.0.0:3000";

    record Address(String host, int port) {
        static Address parse(String raw) {
            int colon = raw.lastIndexOf(':');
            if (colon <= 0) {
                throw new StartupException("bind " + raw + ": invalid socket address syntax");
            }
            try {
                return new Address(raw.substring(0, colon), Integer.parseInt(raw.substring(colon + 1)));
            } catch (NumberFormatException exception) {
                throw new StartupException("bind " + raw + ": invalid socket address syntax");
            }
        }
    }

    static final class StartupException extends RuntimeException {
        StartupException(String message) {
            super(message);
        }
    }

    public static void main(String[] arguments) {
        try {
            run(arguments, System.getenv());
        } catch (StartupException failure) {
            LOG.atError().addKeyValue("error", failure.getMessage()).log("shop-java-api failed");
            System.exit(1);
        }
    }

    static ConfigurableApplicationContext run(String[] arguments, Map<String, String> environment) {
        Address address = Address.parse(environment.getOrDefault("SHOP_API_ADDR", DEFAULT_ADDRESS));
        String databaseUrl = environment.get("DATABASE_URL");
        if (databaseUrl == null) {
            throw new StartupException("DATABASE_URL is not set");
        }
        DataSource pool;
        try {
            pool = PostgresConnection.connect(databaseUrl);
        } catch (RepositoryException.Unavailable unavailable) {
            throw new StartupException("database: Unavailable(\"" + unavailable.reason() + "\")");
        }
        SpringApplication application = new SpringApplication(ShopApplication.class);
        application.setDefaultProperties(Map.of(
                "server.address", address.host(),
                "server.port", Integer.toString(address.port())));
        application.addInitializers(context ->
                ((GenericApplicationContext) context).registerBean(DataSource.class, () -> pool));
        try {
            return application.run(arguments);
        } catch (RuntimeException failure) {
            LOG.atError()
                    .addKeyValue("error", failure.getMessage())
                    .addKeyValue("address", address.host() + ":" + address.port())
                    .log("bind failed");
            throw new StartupException("bind " + address.host() + ":" + address.port() + ": " + failure.getMessage());
        }
    }
}

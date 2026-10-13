package com.cherrytest.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ShopApplicationTest {
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void health_and_openapi_respond() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            int port = freePort();
            String databaseUrl = "postgres://" + postgres.getUsername() + ":" + postgres.getPassword() + "@"
                    + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName();
            try (ConfigurableApplicationContext context = ShopApplication.run(
                    new String[0], Map.of("DATABASE_URL", databaseUrl, "SHOP_API_ADDR", "127.0.0.1:" + port))) {
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> health = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).build(),
                        HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> openapi = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/openapi.json")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(context.isActive()).isTrue();
                assertThat(health.statusCode()).isEqualTo(200);
                assertThat(openapi.body()).contains("shop-java-api");
            }
        }
    }

    @Test
    void refuses_to_start_without_a_database_url() {
        assertThatThrownBy(() -> ShopApplication.run(new String[0], Map.of()))
                .isInstanceOf(ShopApplication.StartupException.class)
                .hasMessage("DATABASE_URL is not set");
    }
}

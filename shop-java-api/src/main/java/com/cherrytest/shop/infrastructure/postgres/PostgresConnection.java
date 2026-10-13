package com.cherrytest.shop.infrastructure.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

public final class PostgresConnection {
    private static final int MAXIMUM_POOL_CONNECTIONS = 5;

    private PostgresConnection() {}

    record Settings(String jdbcUrl, String user, String password) {}

    static Settings settings(String databaseUrl) {
        URI uri;
        try {
            uri = new URI(databaseUrl);
        } catch (URISyntaxException exception) {
            throw Storage.unavailable("connect", exception);
        }
        String scheme = uri.getScheme();
        String userInfo = uri.getRawUserInfo();
        if (scheme == null || !(scheme.equals("postgres") || scheme.equals("postgresql")) || userInfo == null
                || uri.getHost() == null) {
            throw Storage.unavailable("connect", new IllegalArgumentException("not a postgres:// URL"));
        }
        int colon = userInfo.indexOf(':');
        String user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
        String password = colon < 0 ? "" : decode(userInfo.substring(colon + 1));
        int port = uri.getPort() < 0 ? 5432 : uri.getPort();
        return new Settings("jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath(), user, password);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public static DataSource connect(String databaseUrl) {
        Settings settings = settings(databaseUrl);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.user());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(MAXIMUM_POOL_CONNECTIONS);
        HikariDataSource pool;
        try {
            pool = new HikariDataSource(config);
        } catch (RuntimeException exception) {
            throw Storage.unavailable("connect", exception);
        }
        try {
            Flyway.configure().dataSource(pool).load().migrate();
        } catch (RuntimeException exception) {
            pool.close();
            throw Storage.unavailable("migrate", exception);
        }
        return pool;
    }
}

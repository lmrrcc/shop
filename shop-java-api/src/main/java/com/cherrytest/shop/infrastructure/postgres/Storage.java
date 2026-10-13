package com.cherrytest.shop.infrastructure.postgres;

import com.cherrytest.shop.domain.port.RepositoryException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

final class Storage {
    private static final Logger LOG = LoggerFactory.getLogger(Storage.class);

    private Storage() {}

    static RepositoryException.Unavailable unavailable(String operation, Exception error) {
        LOG.atError()
                .addKeyValue("upstream", "postgres")
                .addKeyValue("operation", operation)
                .addKeyValue("error", error.getMessage())
                .log("storage operation failed");
        return new RepositoryException.Unavailable(operation);
    }

    static <T> T checked(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException error) {
            throw unavailable(operation, error);
        }
    }
}

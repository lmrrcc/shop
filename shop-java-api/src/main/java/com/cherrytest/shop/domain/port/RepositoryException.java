package com.cherrytest.shop.domain.port;

import com.cherrytest.shop.domain.model.DomainError;

public abstract sealed class RepositoryException extends RuntimeException
        permits RepositoryException.Rejected, RepositoryException.Unavailable {
    private RepositoryException(String message) {
        super(message);
    }

    public static final class Rejected extends RepositoryException {
        private final DomainError error;

        public Rejected(DomainError error) {
            super(error.message());
            this.error = error;
        }

        public DomainError error() {
            return error;
        }
    }

    public static final class Unavailable extends RepositoryException {
        private final String reason;

        public Unavailable(String reason) {
            super("storage unavailable: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}

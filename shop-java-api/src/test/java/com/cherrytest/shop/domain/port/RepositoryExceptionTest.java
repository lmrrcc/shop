package com.cherrytest.shop.domain.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.cherrytest.shop.domain.model.DomainError;
import org.junit.jupiter.api.Test;

class RepositoryExceptionTest {
    @Test
    void display_includes_the_domain_message_or_the_storage_reason() {
        RepositoryException rejected = new RepositoryException.Rejected(new DomainError.EmptyCart());
        RepositoryException unavailable = new RepositoryException.Unavailable("connect");
        assertThat(rejected.getMessage()).isEqualTo(new DomainError.EmptyCart().message());
        assertThat(unavailable.getMessage()).isEqualTo("storage unavailable: connect");
    }
}

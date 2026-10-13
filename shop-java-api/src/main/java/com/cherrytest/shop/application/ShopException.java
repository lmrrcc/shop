package com.cherrytest.shop.application;

import com.cherrytest.shop.domain.model.DomainError;
import com.cherrytest.shop.domain.model.DomainException;
import com.cherrytest.shop.domain.port.RepositoryException;
import java.util.function.Supplier;

public final class ShopException extends RuntimeException {
    private final ShopError error;

    public ShopException(ShopError error) {
        super(error.message());
        this.error = error;
    }

    public ShopError error() {
        return error;
    }

    static ShopException domain(DomainError error) {
        return new ShopException(new ShopError.Domain(error));
    }

    static <T> T translated(Supplier<T> action) {
        try {
            return action.get();
        } catch (DomainException exception) {
            throw domain(exception.error());
        } catch (RepositoryException.Rejected rejected) {
            throw domain(rejected.error());
        } catch (RepositoryException.Unavailable unavailable) {
            throw new ShopException(new ShopError.Unavailable(unavailable.reason()));
        }
    }
}

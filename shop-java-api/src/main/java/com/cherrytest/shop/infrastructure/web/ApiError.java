package com.cherrytest.shop.infrastructure.web;

import com.cherrytest.shop.application.ShopError;
import com.cherrytest.shop.domain.model.DomainError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

record ApiError(HttpStatus status, String code, String message) {
    private static final Logger LOG = LoggerFactory.getLogger(ApiError.class);

    static ApiError from(DomainError error) {
        return switch (error) {
            case DomainError.InvalidSku _ -> unprocessable("invalid_sku", error);
            case DomainError.InvalidQuantity _ -> unprocessable("invalid_quantity", error);
            case DomainError.InvalidMoney _ -> unprocessable("invalid_money", error);
            case DomainError.InvalidDiscount _ -> unprocessable("invalid_discount", error);
            case DomainError.InvalidCartId _ -> unprocessable("invalid_cart_id", error);
            case DomainError.AmountOverflow _ -> unprocessable("amount_overflow", error);
            case DomainError.EmptyCart _ -> unprocessable("empty_cart", error);
            case DomainError.ProductNotFound _ -> new ApiError(HttpStatus.NOT_FOUND, "product_not_found", error.message());
            case DomainError.CartNotFound _ -> new ApiError(HttpStatus.NOT_FOUND, "cart_not_found", error.message());
            case DomainError.CartClosed _ -> new ApiError(HttpStatus.CONFLICT, "cart_closed", error.message());
            case DomainError.OutOfStock _ -> new ApiError(HttpStatus.CONFLICT, "out_of_stock", error.message());
        };
    }

    static ApiError from(ShopError error) {
        return switch (error) {
            case ShopError.Domain domain -> from(domain.error());
            case ShopError.Unavailable unavailable -> {
                LOG.atError().addKeyValue("operation", unavailable.reason()).log("request failed on storage");
                yield new ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "storage_unavailable", "storage is unavailable");
            }
        };
    }

    static ApiError invalidBody(String message) {
        return new ApiError(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_body", message);
    }

    private static ApiError unprocessable(String code, DomainError error) {
        return new ApiError(HttpStatus.UNPROCESSABLE_CONTENT, code, error.message());
    }

    ResponseEntity<Dtos.ErrorResponse> response() {
        return ResponseEntity.status(status).body(new Dtos.ErrorResponse(new Dtos.ErrorBody(code, message)));
    }
}

package com.cherrytest.shop.infrastructure.web;

import com.cherrytest.shop.application.ShopException;
import com.cherrytest.shop.domain.model.DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(DomainException.class)
    ResponseEntity<Dtos.ErrorResponse> domain(DomainException exception) {
        return ApiError.from(exception.error()).response();
    }

    @ExceptionHandler(ShopException.class)
    ResponseEntity<Dtos.ErrorResponse> shop(ShopException exception) {
        return ApiError.from(exception.error()).response();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Dtos.ErrorResponse> unreadable(HttpMessageNotReadableException exception) {
        return ApiError.invalidBody("Failed to parse the request body as JSON").response();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Dtos.ErrorResponse> mediaType(HttpMediaTypeNotSupportedException exception) {
        return ApiError.invalidBody("Expected request with `Content-Type: application/json`").response();
    }
}

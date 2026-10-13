package com.cherrytest.shop.adapter;

final class AdapterException extends RuntimeException {
    AdapterException(String message) {
        super(message);
    }

    AdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.cherrytest.shop.domain.model;

public record OrderLine(Sku sku, Quantity quantity, Money unitPrice) {}

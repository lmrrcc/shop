package com.cherrytest.shop.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health")
class HealthController {
    @GetMapping("/health")
    @Operation(summary = "Service is up")
    ResponseEntity<Void> health() {
        return ResponseEntity.ok().build();
    }
}

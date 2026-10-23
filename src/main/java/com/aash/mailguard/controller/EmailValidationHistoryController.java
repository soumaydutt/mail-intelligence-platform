package com.aash.mailguard.controller;

import com.aash.mailguard.dto.EmailValidationRequest;
import com.aash.mailguard.dto.EmailValidationResponse;
import com.aash.mailguard.service.EmailValidationHistoryService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/email")
public class EmailValidationHistoryController {

    private final EmailValidationHistoryService service;
    private final byte[] apiKey;
    private final boolean requireApiKey;

    public EmailValidationHistoryController(EmailValidationHistoryService service,
            @Value("${jobs.api-key:}") String apiKey,
            @Value("${validation.require-api-key:false}") boolean requireApiKey) {
        this.service = service;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.requireApiKey = requireApiKey;
    }

    @PostMapping("/test")
    public String test() {
        return "MailGuard API Working";
    }

    @PostMapping("/validate")
    public ResponseEntity<EmailValidationResponse> validate(
            @Valid @RequestBody EmailValidationRequest request,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) {
        if ((requireApiKey || suppliedKey != null) && (suppliedKey == null || apiKey.length == 0 || !MessageDigest.isEqual(
                apiKey, suppliedKey.getBytes(StandardCharsets.UTF_8)))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.validate(request, suppliedKey != null));
    }


}
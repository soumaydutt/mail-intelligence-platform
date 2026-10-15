package com.aash.mailguard.controller;

import com.aash.mailguard.dto.EmailValidationRequest;
import com.aash.mailguard.dto.EmailValidationResponse;
import com.aash.mailguard.service.EmailValidationHistoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/email")
public class EmailValidationHistoryController {

    private final EmailValidationHistoryService service;

    public EmailValidationHistoryController(EmailValidationHistoryService service) {
        this.service = service;
    }

    @PostMapping("/test")
    public String test() {
        return "MailGuard API Working";
    }

    @PostMapping("/validate")
    public EmailValidationResponse validate(
            @Valid @RequestBody EmailValidationRequest request) {

        return service.validate(request);
    }


}
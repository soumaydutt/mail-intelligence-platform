package com.aash.mailguard.dto;

import jakarta.validation.constraints.Size;

public class EmailValidationRequest {
    @Size(max = 255)
    private  String email;

    public EmailValidationRequest() {
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}

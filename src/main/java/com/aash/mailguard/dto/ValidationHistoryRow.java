package com.aash.mailguard.dto;

import com.aash.mailguard.entity.EmailValidationHistory;
import java.time.Instant;

public record ValidationHistoryRow(long id, String email, String domain, Boolean formatValid,
                                   Boolean mxRecordFound, Boolean smtpVerified, String status,
                                   String message, Instant createdAt) {
    public ValidationHistoryRow(EmailValidationHistory history) {
        this(history.getId(), history.getEmail(), history.getDomain(), history.getFormatValid(),
                history.getMxRecordFound(), history.getSmtpVerified(), history.getStatus(),
                history.getMessage(), history.getCreatedAt());
    }
}

package com.aash.mailguard.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "email_validation_history")
public class EmailValidationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255)
    private String email;

    @Column(length = 255)
    private String domain;

    @Column(name = "format_valid")
    private Boolean formatValid;

    @Column(name = "mx_record_found")
    private Boolean mxRecordFound;

    @Column(name = "smtp_verified")
    private Boolean smtpVerified;

    @Column(length = 255)
    private String status;

    @Column(length = 255)
    private String message;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EmailValidationHistory() {

    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public Boolean getFormatValid() {
        return formatValid;
    }

    public void setFormatValid(Boolean formatValid) {
        this.formatValid = formatValid;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Boolean getMxRecordFound() {
        return mxRecordFound;
    }

    public void setMxRecordFound(Boolean mxRecordFound) {
        this.mxRecordFound = mxRecordFound;
    }

    public Boolean getSmtpVerified() {
        return smtpVerified;
    }

    public void setSmtpVerified(Boolean smtpVerified) {
        this.smtpVerified = smtpVerified;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

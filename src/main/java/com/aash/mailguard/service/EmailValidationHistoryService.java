package com.aash.mailguard.service;

import com.aash.mailguard.dns.DnsLookupService;
import com.aash.mailguard.dns.DnsObservation;
import com.aash.mailguard.dto.EmailValidationRequest;
import com.aash.mailguard.dto.EmailValidationResponse;
import com.aash.mailguard.entity.EmailValidationHistory;
import com.aash.mailguard.repository.EmailValidationHistoryRepository;
import com.aash.mailguard.validator.EmailFormatValidator;
import org.springframework.stereotype.Service;


@Service
public class EmailValidationHistoryService {

    private final EmailValidationHistoryRepository repository;
    private final EmailFormatValidator validator;
    private final DnsLookupService dnsLookup;

    public EmailValidationHistoryService(
            EmailValidationHistoryRepository repository,
            EmailFormatValidator validator,
            DnsLookupService dnsLookup) {

        this.repository = repository;
        this.validator=validator;
        this.dnsLookup=dnsLookup;
    }
    public EmailValidationResponse validate(EmailValidationRequest request) {
        EmailValidationResponse response = compute(request).response();
        saveHistory(response);
        return response;
    }

    public record Computation(EmailValidationResponse response, boolean retryable) {
    }

    public Computation compute(EmailValidationRequest request) {
        String email = request.getEmail();

        EmailValidationResponse response = new EmailValidationResponse();
        response.setEmail(email);


        EmailFormatValidator.Result syntax = validator.validate(email);
        response.setFormatValid(syntax.valid());

        if (!syntax.valid()){
            response.setStatus("INVALID");
            response.setMessage("Invalid email format");
            return new Computation(response, false);
        }


        response.setDomain(syntax.domain());


        DnsObservation observation = dnsLookup.lookup(syntax.domain());
        response.setMxRecordFound(observation.legacyMxRecordFound());
        response.setStatus("UNVERIFIED");
        response.setMessage(switch (observation.reason()) {
            case MX_PRESENT, MULTIPLE_MX -> "MX record observed; mailbox not verified.";
            case MX_TARGET_PARTIAL -> "MX route observed with an unresolved target; mailbox not verified.";
            case MX_TARGET_NO_ADDRESS -> "MX record observed without a usable target address; mailbox not verified.";
            case MX_TARGET_TEMPORARY_FAILURE, MX_TARGET_UNCERTAIN ->
                    "MX target address lookup unavailable; mailbox not verified.";
            case DNS_QUERY_BUDGET_EXCEEDED -> observation.mxHosts().isEmpty()
                    ? "DNS lookup unavailable; mailbox not verified."
                    : "MX target address lookup unavailable; mailbox not verified.";
            case IMPLICIT_MX -> "Implicit address route observed; mailbox not verified.";
            case NULL_MX -> "Null MX declares mail not accepted; mailbox not verified.";
            case NO_MAIL_ROUTE, DOMAIN_NOT_FOUND -> "No MX record observed; mailbox not verified.";
            default -> "DNS lookup unavailable; mailbox not verified.";
        });
        return new Computation(response, observation.retryable());
    }

    private void saveHistory(EmailValidationResponse response) {
        EmailValidationHistory history = new EmailValidationHistory();

        history.setEmail(response.getEmail());
        history.setDomain(response.getDomain());
        history.setFormatValid(response.getFormatValid());
        history.setMxRecordFound(response.getMxRecordFound());
        history.setSmtpVerified(response.getSmtpVerified());
        history.setStatus(response.getStatus());
        history.setMessage(response.getMessage());

        repository.save(history);
    }


}
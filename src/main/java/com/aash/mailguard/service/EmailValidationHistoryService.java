package com.aash.mailguard.service;

import com.aash.mailguard.dns.DnsLookupService;
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

        String email = request.getEmail();

        EmailValidationResponse response = new EmailValidationResponse();
        response.setEmail(email);


        EmailFormatValidator.Result syntax = validator.validate(email);
        response.setFormatValid(syntax.valid());

        if (!syntax.valid()){
            response.setStatus("INVALID");
            response.setMessage("Invalid email format");
            saveHistory(response);
            return response;
        }


        response.setDomain(syntax.domain());


        Boolean mxFound = dnsLookup.hasMxRecord(syntax.domain());
        response.setMxRecordFound(mxFound);
        response.setStatus("UNVERIFIED");
        if (Boolean.TRUE.equals(mxFound)) {
            response.setMessage("MX record observed; mailbox not verified.");
        } else if (Boolean.FALSE.equals(mxFound)) {
            response.setMessage("No MX record observed; mailbox not verified.");
        } else {
            response.setMessage("DNS lookup unavailable; mailbox not verified.");
        }
        saveHistory(response);

        return response;
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
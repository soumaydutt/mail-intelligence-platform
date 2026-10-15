package com.aash.mailguard.service;

import com.aash.mailguard.dns.DnsLookupService;
import com.aash.mailguard.dto.EmailValidationRequest;
import com.aash.mailguard.dto.EmailValidationResponse;
import com.aash.mailguard.entity.EmailValidationHistory;
import com.aash.mailguard.repository.EmailValidationHistoryRepository;
import com.aash.mailguard.util.EmailUtil;
import com.aash.mailguard.validator.EmailFormatValidator;
import org.springframework.stereotype.Service;


@Service
public class EmailValidationHistoryService {

    private final EmailValidationHistoryRepository repository;
    private final EmailFormatValidator validator;
    private final DnsLookupService dnsLookup;
    private final EmailUtil util;

    public EmailValidationHistoryService(
            EmailValidationHistoryRepository repository,
            EmailFormatValidator validator,
            DnsLookupService dnsLookup,
            EmailUtil util) {

        this.repository = repository;
        this.validator=validator;
        this.dnsLookup=dnsLookup;
        this.util=util;
    }
    public EmailValidationResponse validate(EmailValidationRequest request) {

        String email = request.getEmail();

        EmailValidationResponse response = new EmailValidationResponse();
        response.setEmail(email);


        boolean formatValid = validator.isFormatValid(email);
        response.setFormatValid(formatValid);

        if (!formatValid){
            response.setStatus("INVALID");
            response.setMessage("Invalid email format");
            saveHistory(response);
            return response;
        }


        String domain= util.extractDomain(email);
        response.setDomain(domain);


        Boolean mxFound = dnsLookup.hasMxRecord(domain);
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
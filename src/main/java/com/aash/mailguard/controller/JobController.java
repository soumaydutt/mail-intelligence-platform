package com.aash.mailguard.controller;

import com.aash.mailguard.jobs.JobStore;
import com.aash.mailguard.jobs.JobStore.JobStatus;
import com.aash.mailguard.jobs.JobStore.Submission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobStore jobs;
    private final ObjectMapper mapper;
    private final byte[] apiKey;

    public JobController(JobStore jobs, ObjectMapper mapper, @Value("${jobs.api-key:}") String apiKey) {
        this.jobs = jobs;
        this.mapper = mapper;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping
    public ResponseEntity<Submission> submit(HttpServletRequest request,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        authenticate(suppliedKey);
        if (idempotencyKey != null && (idempotencyKey.isEmpty() || idempotencyKey.length() > 128
                || !idempotencyKey.chars().allMatch(c -> c >= 33 && c <= 126))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (request.getContentLengthLong() > 8192) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        byte[] payload = request.getInputStream().readNBytes(8193);
        if (payload.length > 8192) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        JsonNode body;
        try {
            body = mapper.readTree(payload);
        } catch (JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        JsonNode emailNode = body == null || !body.isObject() ? null : body.get("email");
        if (emailNode == null || !emailNode.isString()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String email = emailNode.stringValue();
        if (email.isEmpty() || email.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        Submission submission = jobs.submit(email, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, URI.create("/api/jobs/" + submission.id()).toString())
                .body(submission);
    }

    @GetMapping("/{id}")
    public JobStatus get(@PathVariable String id,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) {
        authenticate(suppliedKey);
        return jobs.find(parseId(id)).map(this::safeStatus)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @DeleteMapping("/{id}")
    public JobStatus cancel(@PathVariable String id,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) {
        authenticate(suppliedKey);
        UUID jobId = parseId(id);
        if (!jobs.cancel(jobId)) {
            throw new ResponseStatusException(jobs.find(jobId).isPresent()
                    ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND);
        }
        return jobs.find(jobId).map(this::safeStatus)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private JobStatus safeStatus(JobStatus status) {
        if (status.result() == null || "COMPLETED".equals(status.state())) {
            return status;
        }
        return new JobStatus(status.id(), status.state(), status.attempts(), status.maxAttempts(),
                status.dueAt(), status.createdAt(), status.finishedAt(), status.failureCode(), null);
    }

    private void authenticate(String suppliedKey) {
        if (apiKey.length == 0 || suppliedKey == null || !MessageDigest.isEqual(
                apiKey, suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private UUID parseId(String id) {
        try {
            UUID parsed = UUID.fromString(id);
            if (!parsed.toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}

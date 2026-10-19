package com.aash.mailguard.delivery;

import com.aash.mailguard.delivery.DeliveryEvidenceService.EventInput;
import com.aash.mailguard.delivery.DeliveryEvidenceService.EventType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;
@RestController
@RequestMapping("/api")
public class DeliveryController {
    private static final int MAX_BODY_BYTES = 8192;
    private static final Set<String> EVENT_FIELDS = Set.of(
            "email", "eventType", "externalEventId", "occurredAt", "enhancedStatusCode");
    private static final Set<String> EMAIL_FIELDS = Set.of("email");
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    private static final Pattern ENHANCED_CODE = Pattern.compile("[!-?A-~]+");
    private final DeliveryEvidenceService evidence;
    private final ObjectReader strictReader;
    private final byte[] apiKey;

    public DeliveryController(DeliveryEvidenceService evidence, ObjectMapper mapper,
            @Value("${jobs.api-key:}") String apiKey) {
        this.evidence = evidence;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.strictReader = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    @PostMapping("/delivery-events")
    public ResponseEntity<DeliveryEvidenceService.Ingested> ingest(HttpServletRequest request,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) throws IOException {
        authenticate(suppliedKey);
        JsonNode body = body(request, EVENT_FIELDS);
        String email = text(body, "email", 255);
        String type = text(body, "eventType", 32);
        EventType eventType;
        try {
            eventType = EventType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
        String externalId = text(body, "externalEventId", 255);
        if (!EXTERNAL_ID.matcher(externalId).matches()) {
            throw invalid();
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(text(body, "occurredAt", 40));
        } catch (DateTimeParseException ex) {
            throw invalid();
        }
        String code = optionalText(body, "enhancedStatusCode", 32);
        if (code != null && !ENHANCED_CODE.matcher(code).matches()) {
            throw invalid();
        }
        var result = evidence.ingest(new EventInput(email, eventType, externalId, occurredAt, code));
        return ResponseEntity.status(result.reused() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(result);
    }
    @PostMapping("/delivery-evidence/lookup")
    public ResponseEntity<DeliveryEvidenceService.Snapshot> lookup(HttpServletRequest request,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) throws IOException {
        authenticate(suppliedKey);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(evidence.lookup(text(body(request, EMAIL_FIELDS), "email", 255)));
    }
    private void authenticate(String suppliedKey) {
        if (apiKey.length == 0 || suppliedKey == null || !MessageDigest.isEqual(
                apiKey, suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private JsonNode body(HttpServletRequest request, Set<String> allowed) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        }
        JsonNode node;
        try {
            node = strictReader.readTree(bytes);
        } catch (JacksonException ex) {
            throw invalid();
        }
        if (node == null || !node.isObject() || node.propertyNames().stream().anyMatch(name -> !allowed.contains(name))) {
            throw invalid();
        }
        return node;
    }

    private static String text(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isEmpty()
                || value.stringValue().length() > maxLength) {
            throw invalid();
        }
        return value.stringValue();
    }

    private static String optionalText(JsonNode node, String field, int maxLength) {
        if (!node.has(field)) {
            return null;
        }
        return text(node, field, maxLength);
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
}

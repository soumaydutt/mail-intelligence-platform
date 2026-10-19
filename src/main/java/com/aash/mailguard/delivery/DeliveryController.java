package com.aash.mailguard.delivery;

import com.aash.mailguard.delivery.DeliveryEvidenceService.EventInput;
import com.aash.mailguard.delivery.DeliveryEvidenceService.EventType;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class DeliveryController {
    private static final int MAX_BODY_BYTES = 8192;
    private static final Set<String> EVENT_FIELDS = Set.of(
            "email", "eventType", "externalEventId", "occurredAt", "enhancedStatusCode");
    private static final Set<String> EMAIL_FIELDS = Set.of("email");
    private static final Set<String> REASON_FIELDS = Set.of("reasonCode");
    private static final Set<String> SUPPRESS_FIELDS = Set.of("email", "reasonCode");
    private static final Set<String> QUERY_FIELDS = Set.of("after", "limit");
    private static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");
    private static final Pattern ENHANCED_CODE = Pattern.compile("[!-?A-~]+");
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]*");

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
    @GetMapping("/suppressions")
    public ResponseEntity<List<DeliveryEvidenceService.Suppression>> suppressions(HttpServletRequest request,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) {
        authenticate(suppliedKey);
        Map<String, String[]> params = request.getParameterMap();
        if (params.keySet().stream().anyMatch(key -> !QUERY_FIELDS.contains(key))
                || params.values().stream().anyMatch(values -> values.length != 1)) {
            throw invalid();
        }
        UUID after = params.containsKey("after") ? parseId(params.get("after")[0]) : null;
        int limit = 50;
        if (params.containsKey("limit")) {
            try {
                limit = Integer.parseInt(params.get("limit")[0]);
            } catch (NumberFormatException ex) {
                throw invalid();
            }
        }
        if (limit < 1 || limit > 100) {
            throw invalid();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(evidence.suppressions(after, limit));
    }

    @PostMapping("/suppressions")
    public ResponseEntity<Map<String, UUID>> suppress(HttpServletRequest request,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) throws IOException {
        authenticate(suppliedKey);
        JsonNode body = body(request, SUPPRESS_FIELDS);
        UUID id = evidence.suppress(text(body, "email", 255), reason(body));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("id", id));
    }

    @DeleteMapping("/suppressions/{id}")
    public ResponseEntity<Void> lift(@PathVariable String id, HttpServletRequest request,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) throws IOException {
        authenticate(suppliedKey);
        UUID suppressionId = parseId(id);
        evidence.lift(suppressionId, reason(body(request, REASON_FIELDS)));
        return noContent();
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

    private static String reason(JsonNode body) {
        String reason = text(body, "reasonCode", 64);
        if (!REASON.matcher(reason).matches()) {
            throw invalid();
        }
        return reason;
    }

    private static UUID parseId(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (value.equalsIgnoreCase(id.toString())) {
                return id;
            }
        } catch (IllegalArgumentException ex) {
            // Invalid identifiers have the same fixed error as malformed JSON.
        }
        throw invalid();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
}

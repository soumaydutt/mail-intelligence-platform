package com.aash.mailguard.imports;

import com.aash.mailguard.imports.BulkImportStore.Progress;
import com.aash.mailguard.imports.BulkImportStore.ResultRow;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/imports")
public class BulkImportController {
    private static final Logger log = LoggerFactory.getLogger(BulkImportController.class);
    private static final String CSV_HEADER = "ordinal,email,rowState,duplicateOf,reason,jobState,resultStatus,failureCode,formatValid,mxRecordFound,smtpVerified,domain,message\r\n";

    private final BulkImportStore store;
    private final CsvImportService csv;
    private final byte[] apiKey;
    private final int pageSize;

    public BulkImportController(BulkImportStore store, CsvImportService csv,
            @Value("${jobs.api-key:}") String apiKey,
            @Value("${imports.results-page-size:500}") int pageSize) {
        this.store = store;
        this.csv = csv;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.pageSize = pageSize;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, UUID>> create(HttpServletRequest request,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey)
            throws IOException, ServletException {
        authenticate(suppliedKey);
        UUID id = csv.ingest(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.LOCATION, "/api/imports/" + id)
                .body(Map.of("id", id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Progress> get(@PathVariable String id,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) {
        authenticate(suppliedKey);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(find(parseId(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Progress> cancel(@PathVariable String id,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) {
        authenticate(suppliedKey);
        UUID importId = parseId(id);
        if (!store.cancel(importId)) {
            throw new ResponseStatusException(store.find(importId).isEmpty()
                    ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT);
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(find(importId));
    }

    @GetMapping("/{id}/results")
    public ResponseEntity<List<ResultRow>> results(@PathVariable String id, HttpServletRequest request,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) {
        authenticate(suppliedKey);
        UUID importId = parseId(id);
        long after = queryLong(request.getParameter("after"), 0);
        long limit = queryLong(request.getParameter("limit"), 100);
        if (after < 0 || limit < 1 || limit > pageSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        find(importId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(store.results(importId, after, (int) limit));
    }

    @GetMapping(value = "/{id}/export", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable String id,
            @RequestHeader(value = "X-Job-API-Key", required = false) String suppliedKey) {
        authenticate(suppliedKey);
        UUID importId = parseId(id);
        find(importId);
        StreamingResponseBody stream = output -> {
            long started = System.nanoTime();
            long exportedRows = 0;
            boolean complete = false;
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                writer.write(CSV_HEADER);
                long after = 0;
                List<ResultRow> page;
                do {
                    page = store.results(importId, after, pageSize);
                    for (ResultRow row : page) {
                        writeRow(writer, row);
                        after = row.ordinal();
                        exportedRows++;
                    }
                } while (page.size() == pageSize);
                complete = true;
            } finally {
                log.info("Import export rows={} duration_ms={} complete={}", exportedRows,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), complete);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=import-results.csv")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(stream);
    }

    private static void writeRow(BufferedWriter writer, ResultRow row) throws IOException {
        writer.write(Long.toString(row.ordinal()));
        cell(writer, row.email());
        cell(writer, row.rowState());
        cell(writer, row.duplicateOf());
        cell(writer, row.reason());
        cell(writer, row.jobState());
        cell(writer, row.resultStatus());
        cell(writer, row.failureCode());
        cell(writer, row.formatValid());
        cell(writer, row.mxRecordFound());
        cell(writer, row.smtpVerified());
        cell(writer, row.domain());
        cell(writer, row.message());
        writer.write("\r\n");
    }

    private static void cell(BufferedWriter writer, Object value) throws IOException {
        writer.write(',');
        if (value == null) {
            return;
        }
        String text = value.toString();
        // Leading whitespace can be discarded by spreadsheets before formula evaluation.
        boolean formula = !text.isEmpty() && (Character.isWhitespace(text.charAt(0))
                || text.charAt(0) == '\ufeff' || text.charAt(0) == '\0'
                || "=+-@".indexOf(text.charAt(0)) >= 0);
        writer.write('"');
        if (formula) {
            writer.write('\'');
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                writer.write('"');
            }
            writer.write(ch);
        }
        writer.write('"');
    }

    private Progress find(UUID id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void authenticate(String suppliedKey) {
        if (apiKey.length == 0 || suppliedKey == null || !MessageDigest.isEqual(
                apiKey, suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private static UUID parseId(String id) {
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

    private static long queryLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}

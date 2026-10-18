package com.aash.mailguard.imports;

import com.aash.mailguard.imports.BulkImportStore.InputRow;
import com.univocity.parsers.common.TextParsingException;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import com.univocity.parsers.csv.UnescapedQuoteHandling;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;

@Service
public class CsvImportService {
    private static final Logger log = LoggerFactory.getLogger(CsvImportService.class);
    private final LongAdder created = new LongAdder();
    private final LongAdder staged = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rowsIngested = new LongAdder();
    private final LongAdder ingestionMillis = new LongAdder();
    private final BulkImportStore store;
    private final Semaphore uploads;
    private final long maxBytes;
    private final int maxRows;
    private final int maxRecordChars;
    private final int maxEmailChars;
    private final int batchSize;

    public CsvImportService(BulkImportStore store,
            @Value("${imports.max-concurrent-uploads:2}") int maxConcurrent,
            @Value("${imports.max-upload-bytes:16777216}") long maxBytes,
            @Value("${imports.max-rows:50000}") int maxRows,
            @Value("${imports.max-record-chars:2048}") int maxRecordChars,
            @Value("${imports.max-email-chars:255}") int maxEmailChars,
            @Value("${imports.batch-size:250}") int batchSize) {
        if (maxConcurrent < 1 || maxConcurrent > 2 || maxBytes < 1 || maxBytes > 16777216
                || maxRows < 1 || maxRows > 50000 || maxRecordChars < 1 || maxRecordChars > 2048
                || maxEmailChars < 1 || maxEmailChars > 255 || batchSize < 1 || batchSize > 250) {
            throw new IllegalArgumentException("Invalid CSV import configuration");
        }
        this.store = store;
        this.uploads = new Semaphore(maxConcurrent);
        this.maxBytes = maxBytes;
        this.maxRows = maxRows;
        this.maxRecordChars = maxRecordChars;
        this.maxEmailChars = maxEmailChars;
        this.batchSize = batchSize;
    }

    public UUID ingest(HttpServletRequest request) throws IOException, ServletException {
        if (!uploads.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        try {
            // Acquire admission before the servlet parses and spools the multipart body.
            if (request.getParts().size() != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            Part file = request.getPart("file");
            if (file == null || !"file".equals(file.getName()) || file.getSubmittedFileName() == null
                    || !file.getSubmittedFileName().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")
                    || file.getSubmittedFileName().length() > 255
                    || file.getSubmittedFileName().chars().anyMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\' || c == ':')
                    || file.getContentType() == null
                    || !file.getContentType().matches("(?i)text/csv(?:\\s*;\\s*charset\\s*=\\s*\\\"?utf-8\\\"?)?")
                    || file.getSize() > maxBytes) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            UUID id = store.begin();
            created.increment();
            long started = System.nanoTime();
            boolean success = false;
            try {
                parse(file, id);
                store.finish(id);
                staged.increment();
                success = true;
                return id;
            } catch (InvalidCsv ex) {
                store.fail(id, ex.code);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            } catch (TextParsingException ex) {
                store.fail(id, "INVALID_CSV");
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            } catch (IOException ex) {
                store.fail(id, "INVALID_CSV");
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            } catch (RuntimeException ex) {
                if (!(ex instanceof ResponseStatusException)) {
                    store.fail(id, "IMPORT_FAILED");
                }
                throw ex;
            } finally {
                if (!success) {
                    failed.increment();
                }
                ingestionMillis.add(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                log.info("Import ingestion totals created={} staged={} failed={} rows={} duration_ms={}",
                        created.sum(), staged.sum(), failed.sum(), rowsIngested.sum(), ingestionMillis.sum());
            }
        } finally {
            uploads.release();
        }
    }

    private void parse(Part file, UUID id) throws IOException {
        CsvParserSettings settings = new CsvParserSettings();
        settings.setSkipEmptyLines(false);
        settings.setReadInputOnSeparateThread(false);
        settings.setInputBufferSize(4096);
        settings.setMaxCharsPerColumn(maxRecordChars);
        settings.setMaxColumns(maxRecordChars + 1);
        settings.setUnescapedQuoteHandling(UnescapedQuoteHandling.RAISE_ERROR);
        settings.setLineSeparatorDetectionEnabled(true);
        settings.setAutoClosingEnabled(false);
        CsvParser parser = new CsvParser(settings);
        try (InputStream input = new LimitedInputStream(file.getInputStream(), maxBytes);
                PushbackInputStream bytes = new PushbackInputStream(input, 3)) {
            byte[] first = bytes.readNBytes(3);
            if (first.length != 3 || (first[0] & 255) != 0xef || (first[1] & 255) != 0xbb
                    || (first[2] & 255) != 0xbf) {
                bytes.unread(first);
            }
            try (Reader reader = new RecordLimitedReader(new InputStreamReader(bytes,
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)), maxRecordChars)) {
                parser.beginParsing(reader);
                String[] header = parser.parseNext();
                if (header == null) {
                    throw new InvalidCsv("INVALID_HEADER");
                }
                int emailColumn = -1;
                for (int i = 0; i < header.length; i++) {
                    if ("email".equalsIgnoreCase(header[i])) {
                        if (emailColumn >= 0) {
                            throw new InvalidCsv("INVALID_HEADER");
                        }
                        emailColumn = i;
                    }
                }
                if (emailColumn < 0) {
                    throw new InvalidCsv("INVALID_HEADER");
                }
                List<InputRow> batch = new ArrayList<>(batchSize);
                long ordinal = 0;
                String[] row;
                while ((row = parser.parseNext()) != null) {
                    if (++ordinal > maxRows) {
                        throw new InvalidCsv("TOO_MANY_ROWS");
                    }
                    String email = emailColumn < row.length ? row[emailColumn] : null;
                    String reason = email == null || email.isBlank() ? "EMPTY_EMAIL"
                            : email.length() > maxEmailChars ? "EMAIL_TOO_LONG" : null;
                    batch.add(new InputRow(ordinal, "EMAIL_TOO_LONG".equals(reason) ? null : email, reason));
                    if (batch.size() == batchSize) {
                        store.append(id, batch);
                        rowsIngested.add(batch.size());
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    store.append(id, batch);
                    rowsIngested.add(batch.size());
                }
            } finally {
                parser.stopParsing();
            }
        }
    }

    @PreDestroy
    public void report() {
        log.info("Import ingestion totals created={} staged={} failed={} rows={} duration_ms={}",
                created.sum(), staged.sum(), failed.sum(), rowsIngested.sum(), ingestionMillis.sum());
    }

    private static final class InvalidCsv extends RuntimeException {
        private final String code;

        InvalidCsv(String code) {
            this.code = code;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long read;

        LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1 && ++read > limit) {
                throw new IOException("CSV byte limit");
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count > 0 && (read += count) > limit) {
                throw new IOException("CSV byte limit");
            }
            return count;
        }
    }

    private static final class RecordLimitedReader extends FilterReader {
        private final int limit;
        private int length;
        private boolean quoted;
        private final char[] single = new char[1];

        RecordLimitedReader(Reader reader, int limit) {
            super(reader);
            this.limit = limit;
        }

        @Override
        public int read(char[] chars, int offset, int count) throws IOException {
            int size = in.read(chars, offset, count);
            if (size == -1 && quoted) {
                throw new IOException("Unterminated CSV quote");
            }
            for (int i = offset; i < offset + Math.max(0, size); i++) {
                char c = chars[i];
                if ((c == '\r' || c == '\n') && !quoted) {
                    length = 0;
                } else if (++length > limit) {
                    throw new IOException("CSV record limit");
                }
                if (c == '"') {
                    quoted = !quoted;
                }
            }
            return size;
        }

        @Override
        public int read() throws IOException {
            return read(single, 0, 1) == -1 ? -1 : single[0];
        }
    }
}

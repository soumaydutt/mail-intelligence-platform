package com.aash.mailguard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aash.mailguard.dns.DnsLookupService;
import com.aash.mailguard.entity.EmailValidationHistory;
import com.aash.mailguard.repository.EmailValidationHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mailguard;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.mvc.log-resolved-exception=true"
})
class EmailValidationApiTest {
    private static final Logger RESOLVER_LOGGER =
            (Logger) LoggerFactory.getLogger(ExceptionHandlerExceptionResolver.class);
    private ListAppender<ILoggingEvent> resolvedErrors;


    @LocalServerPort
    int port;

    @Autowired
    EmailValidationHistoryRepository repository;
    @MockitoBean
    DnsLookupService dnsLookup;
    @BeforeEach
    void clearHistory() {
        resolvedErrors = new ListAppender<>();
        resolvedErrors.start();
        RESOLVER_LOGGER.addAppender(resolvedErrors);
        repository.deleteAll();
    }

    @AfterEach
    void stopLogCapture() {
        RESOLVER_LOGGER.detachAppender(resolvedErrors);
        resolvedErrors.stop();
    }



    @Test
    void invalidSyntaxReturnsInvalidWithoutNetworkAndRecordsHistory() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"invalid\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"INVALID\""));
            assertTrue(response.body().contains("\"formatValid\":false"));
        }
        assertEquals(1, repository.count());
    }

    @Test
    void absentMxIsUnverifiedRatherThanInvalid() throws Exception {
        when(dnsLookup.hasMxRecord("example.test")).thenReturn(false);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@example.test\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"UNVERIFIED\""));
            assertTrue(response.body().contains("\"mxRecordFound\":false"));
            assertTrue(response.body().contains("\"smtpVerified\":null"));
        }
    }

    @Test
    void observedMxDoesNotTriggerSmtpOrClaimValidity() throws Exception {
        when(dnsLookup.hasMxRecord("example.test")).thenReturn(true);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@example.test\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"UNVERIFIED\""));
            assertTrue(response.body().contains("\"mxRecordFound\":true"));
            assertTrue(response.body().contains("\"smtpVerified\":null"));
        }
        EmailValidationHistory history = repository.findAll().getFirst();
        assertEquals("UNVERIFIED", history.getStatus());
        assertNull(history.getSmtpVerified());
    }

    @Test
    void dnsFailureIsUnknownNotInvalid() throws Exception {
        when(dnsLookup.hasMxRecord("example.test")).thenReturn(null);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@example.test\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"UNVERIFIED\""));
            assertTrue(response.body().contains("\"mxRecordFound\":null"));
        }
        assertNull(repository.findAll().getFirst().getMxRecordFound());
    }

    @Test
    void controlCharactersAreRejectedBeforeDns() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a\\r\\n@example.test\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"INVALID\""));
        }
        verifyNoInteractions(dnsLookup);
    }

    @Test
    void addressAtStorageLimitPreservesResponseAndHistory() throws Exception {
        String syntheticAddress = "x".repeat(242) + "@fixture.test";
        when(dnsLookup.hasMxRecord("fixture.test")).thenReturn(false);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + syntheticAddress + "\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"UNVERIFIED\""));
            assertTrue(response.body().contains("\"mxRecordFound\":false"));
        }
        assertEquals(syntheticAddress, repository.findAll().getFirst().getEmail());
    }

    @Test
    void oversizedAddressFailsBeforePersistenceWithoutDisclosure() throws Exception {
        String syntheticAddress = "test-sentinel-" + "x".repeat(300);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + syntheticAddress + "\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode());
            assertTrue(response.body().contains("\"message\":\"Internal server error\""));
            assertTrue(response.body().contains("\"path\":\"/api/email/validate\""));
            assertFalse(response.body().contains("test-sentinel-"));
            assertFalse(response.body().contains("SQL"));
        }
        assertEquals(0, repository.count());
        verifyNoInteractions(dnsLookup);
        assertFalse(resolvedErrors.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("test-sentinel-")));
    }

    @Test
    void unknownPathDoesNotEchoAddressOrExceptionDetails() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/email/test-sentinel%40fixture.invalid")).GET().build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode());
            assertTrue(response.body().contains("\"message\":\"Internal server error\""));
            assertTrue(response.body().contains("\"path\":\"/\""));
            assertFalse(response.body().contains("fixture.invalid"));
            assertFalse(response.body().contains("No static resource"));
        }
        assertFalse(resolvedErrors.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("fixture.invalid")));
    }

    @Test
    void openApiDescribesValidationEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .GET().build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("/api/email/validate"));
        }
    }
}

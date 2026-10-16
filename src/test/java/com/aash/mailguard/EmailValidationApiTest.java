package com.aash.mailguard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aash.mailguard.dns.DnsLookupService;
import com.aash.mailguard.dns.DnsObservation;
import com.aash.mailguard.dns.DnsObservation.Outcome;
import com.aash.mailguard.dns.DnsObservation.Reason;
import com.aash.mailguard.entity.EmailValidationHistory;
import com.aash.mailguard.repository.EmailValidationHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
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
    private static final ObjectMapper objectMapper = new ObjectMapper();
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
        verifyNoInteractions(dnsLookup);
    }

    @Test
    void whitespaceInAddressKeepsInvalidResponseContract() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"user name@example.test\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"INVALID\""));
            assertTrue(response.body().contains("\"formatValid\":false"));
        }
        assertEquals(1, repository.count());
        assertFalse(repository.findAll().getFirst().getFormatValid());
        verifyNoInteractions(dnsLookup);
    }

    @Test
    void absentMxIsUnverifiedRatherThanInvalid() throws Exception {
        when(dnsLookup.lookup("example.test")).thenReturn(observation(Outcome.NO_MAIL_ROUTE, Reason.NO_MAIL_ROUTE, "example.test"));
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
            assertTrue(response.body().contains("\"message\":\"No MX record observed; mailbox not verified.\""));
        }
    }

    @Test
    void implicitNullAndNxdomainKeepTheirLegacyMxMeaningInResponseAndHistory() throws Exception {
        assertDnsResponse(Outcome.MAIL_ROUTE, Reason.IMPLICIT_MX, "implicit.test", false,
                "Implicit address route observed; mailbox not verified.");
        assertDnsResponse(Outcome.MAIL_NOT_ACCEPTED, Reason.NULL_MX, "nullmx.test", false,
                "Null MX declares mail not accepted; mailbox not verified.");
        assertDnsResponse(Outcome.NO_MAIL_ROUTE, Reason.DOMAIN_NOT_FOUND, "nxdomain.test", false,
                "No MX record observed; mailbox not verified.");
    }

    @Test
    void advertisedMultipleMxAndMalformedDnsStayUnverifiedWithDistinctLegacyValues() throws Exception {
        assertDnsResponse(Outcome.MAIL_ROUTE, Reason.MULTIPLE_MX, "multiple.test", true,
                "MX record observed; mailbox not verified.");
        assertDnsResponse(Outcome.UNKNOWN, Reason.DNS_MALFORMED_RESPONSE, "malformed.test", null,
                "DNS lookup unavailable; mailbox not verified.");
    }

    @Test
    void unresolvedMxTargetsRemainUnverifiedWithoutErasingObservedMx() throws Exception {
        assertDnsResponse(Outcome.UNKNOWN, Reason.MX_TARGET_TEMPORARY_FAILURE, "mx-temp.test", true,
                "MX target address lookup unavailable; mailbox not verified.");
        assertDnsResponse(Outcome.MAIL_ROUTE, Reason.MX_TARGET_PARTIAL, "mx-partial.test", true,
                "MX route observed with an unresolved target; mailbox not verified.");
        assertDnsResponse(Outcome.NO_MAIL_ROUTE, Reason.MX_TARGET_NO_ADDRESS, "mx-no-address.test", true,
                "MX record observed without a usable target address; mailbox not verified.");
        assertDnsResponse(Outcome.UNKNOWN, Reason.MX_TARGET_UNCERTAIN, "mx-malformed.test", true,
                "MX target address lookup unavailable; mailbox not verified.");
    }

    @Test
    void exhaustedMxQueryBudgetKeepsLegacyResponseAndHistory() throws Exception {
        assertDnsResponse(Outcome.UNKNOWN, Reason.DNS_QUERY_BUDGET_EXCEEDED, "mx-budget.test", true,
                "MX target address lookup unavailable; mailbox not verified.");
    }


    @Test
    void observedMxDoesNotTriggerSmtpOrClaimValidity() throws Exception {
        when(dnsLookup.lookup("example.test")).thenReturn(observation(Outcome.MAIL_ROUTE, Reason.MX_PRESENT, "example.test"));
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
            assertTrue(response.body().contains("\"message\":\"MX record observed; mailbox not verified.\""));
        }
        EmailValidationHistory history = repository.findAll().getFirst();
        assertEquals("UNVERIFIED", history.getStatus());
        assertNull(history.getSmtpVerified());
    }

    @Test
    void dnsFailureIsUnknownNotInvalid() throws Exception {
        when(dnsLookup.lookup("example.test")).thenReturn(observation(Outcome.UNKNOWN, Reason.DNS_TEMPORARY_FAILURE, "example.test"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@example.test\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"UNVERIFIED\""));
            assertTrue(response.body().contains("\"mxRecordFound\":null"));
            assertTrue(response.body().contains("\"message\":\"DNS lookup unavailable; mailbox not verified.\""));
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
    void addressAtSmtpLimitPreservesResponseAndHistory() throws Exception {
        String domain = longTestDomain(56);
        String syntheticAddress = "x".repeat(64) + "@" + domain;
        assertEquals(254, syntheticAddress.length());
        when(dnsLookup.lookup(domain)).thenReturn(observation(Outcome.NO_MAIL_ROUTE, Reason.NO_MAIL_ROUTE, domain));

        HttpResponse<String> response = sendValidation(syntheticAddress);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(syntheticAddress, body.path("email").asText());
        assertEquals(domain, body.path("domain").asText());
        assertEquals("UNVERIFIED", body.path("status").asText());
        assertFalse(body.path("mxRecordFound").asBoolean());

        assertEquals(1, repository.count());
        EmailValidationHistory history = repository.findAll().getFirst();
        assertEquals(syntheticAddress, history.getEmail());
        assertEquals(domain, history.getDomain());
        assertEquals("UNVERIFIED", history.getStatus());
        verify(dnsLookup).lookup(domain);
    }

    @Test
    void plusAddressKeepsLocalPartAndNormalizesUppercaseDomain() throws Exception {
        String email = "First.Last+tag@EXAMPLE.TEST";
        when(dnsLookup.lookup("example.test")).thenReturn(observation(Outcome.NO_MAIL_ROUTE, Reason.NO_MAIL_ROUTE, "example.test"));

        HttpResponse<String> response = sendValidation(email);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(email, body.path("email").asText());
        assertEquals("example.test", body.path("domain").asText());
        assertTrue(body.path("formatValid").asBoolean());
        assertEquals("UNVERIFIED", body.path("status").asText());
        EmailValidationHistory history = repository.findAll().getFirst();
        assertEquals(1, repository.count());
        assertEquals(email, history.getEmail());
        assertEquals("example.test", history.getDomain());
        verify(dnsLookup).lookup("example.test");
    }

    @Test
    void quotedLocalAllowsAtAndEscapedQuotesWithoutChangingEmail() throws Exception {
        String email = "\"Name@\\\"Unit\\\"\"@EXAMPLE.TEST";
        when(dnsLookup.lookup("example.test")).thenReturn(observation(Outcome.NO_MAIL_ROUTE, Reason.NO_MAIL_ROUTE, "example.test"));

        HttpResponse<String> response = sendValidation(email);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(email, body.path("email").asText());
        assertEquals("example.test", body.path("domain").asText());
        assertTrue(body.path("formatValid").asBoolean());
        assertEquals("UNVERIFIED", body.path("status").asText());
        EmailValidationHistory history = repository.findAll().getFirst();
        assertEquals(1, repository.count());
        assertEquals(email, history.getEmail());
        assertEquals("example.test", history.getDomain());
        verify(dnsLookup).lookup("example.test");
    }

    @Test
    void unicodeDomainUsesAsciiIdnaForDnsResponseAndHistory() throws Exception {
        String email = "User@BÜCHER.TEST";
        String domain = "xn--bcher-kva.test";
        when(dnsLookup.lookup(domain)).thenReturn(observation(Outcome.NO_MAIL_ROUTE, Reason.NO_MAIL_ROUTE, domain));

        HttpResponse<String> response = sendValidation(email);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(email, body.path("email").asText());
        assertEquals(domain, body.path("domain").asText());
        assertTrue(body.path("formatValid").asBoolean());
        assertEquals("UNVERIFIED", body.path("status").asText());
        EmailValidationHistory history = repository.findAll().getFirst();
        assertEquals(1, repository.count());
        assertEquals(email, history.getEmail());
        assertEquals(domain, history.getDomain());
        verify(dnsLookup).lookup(domain);
    }

    @Test
    void malformedAndUnsafeAddressesStayInvalidWithoutDnsAndRecordHistory() throws Exception {
        for (String email : new String[] {"müller@example.test", "a@faß.test"}) {
            HttpResponse<String> response = sendValidation(email);
            assertEquals(200, response.statusCode(), email);
            JsonNode body = objectMapper.readTree(response.body());
            assertEquals(email, body.path("email").asText(), email);
            assertEquals("INVALID", body.path("status").asText(), email);
            assertFalse(body.path("formatValid").asBoolean(), email);
            assertTrue(body.path("domain").isNull(), email);
            assertTrue(body.path("mxRecordFound").isNull(), email);
            assertTrue(body.path("smtpVerified").isNull(), email);
            assertEquals(1, repository.count(), email);
            EmailValidationHistory history = repository.findAll().getFirst();
            assertEquals(email, history.getEmail(), email);
            assertEquals("INVALID", history.getStatus(), email);
            assertFalse(history.getFormatValid(), email);
            assertNull(history.getDomain(), email);
            assertNull(history.getMxRecordFound(), email);
            verifyNoInteractions(dnsLookup);
            repository.deleteAll();
        }
    }

    @Test
    void numericFinalLabelIsInvalidBeforeDnsAndRetainsHistory() throws Exception {
        String email = "user@example.123";
        HttpResponse<String> response = sendValidation(email);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(email, body.path("email").asText());
        assertEquals("INVALID", body.path("status").asText());
        assertFalse(body.path("formatValid").asBoolean());
        assertTrue(body.path("domain").isNull());
        assertTrue(body.path("mxRecordFound").isNull());
        assertEquals(1, repository.count());
        EmailValidationHistory history = repository.findAll().getFirst();
        assertEquals(email, history.getEmail());
        assertEquals("INVALID", history.getStatus());
        assertNull(history.getDomain());
        verifyNoInteractions(dnsLookup);
    }

    @Test
    void otherwiseWellFormed255CharacterAddressIsInvalidAndPersisted() throws Exception {
        String email = "x".repeat(64) + "@" + longTestDomain(57);
        assertEquals(255, email.length());

        HttpResponse<String> response = sendValidation(email);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(email, body.path("email").asText());
        assertEquals("INVALID", body.path("status").asText());
        assertFalse(body.path("formatValid").asBoolean());
        assertTrue(body.path("domain").isNull());
        assertTrue(body.path("mxRecordFound").isNull());
        assertEquals(1, repository.count());
        EmailValidationHistory history = repository.findAll().getFirst();
        assertEquals(email, history.getEmail());
        assertEquals("INVALID", history.getStatus());
        assertFalse(history.getFormatValid());
        assertNull(history.getDomain());
        assertNull(history.getMxRecordFound());
        verifyNoInteractions(dnsLookup);
    }

    @Test
    void oversizedAddressReturnsBadRequestWithoutPersistenceOrDisclosure() throws Exception {
        String syntheticAddress = "test-sentinel-" + "x".repeat(64 - "test-sentinel-".length())
                + "@" + longTestDomain(58);
        assertEquals(256, syntheticAddress.length());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + syntheticAddress + "\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("\"message\":\"Invalid request\""));
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
    void malformedJsonReturnsBadRequestWithoutDisclosure() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"test-sentinel-"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("\"message\":\"Invalid request\""));
            assertTrue(response.body().contains("\"path\":\"/api/email/validate\""));
            assertFalse(response.body().contains("test-sentinel-"));
        }
        assertEquals(0, repository.count());
        verifyNoInteractions(dnsLookup);
    }

    @Test
    void knownTestRouteIsNotRedactedInErrorResponses() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/test"))
                .GET().build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode());
            assertTrue(response.body().contains("\"path\":\"/api/email/test\""));
        }
    }

    @Test
    void serverFailureRemainsSanitized() throws Exception {
        when(dnsLookup.lookup("fixture.test")).thenThrow(new IllegalStateException("test-sentinel-secret"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@fixture.test\"}"))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode());
            assertTrue(response.body().contains("\"message\":\"Internal server error\""));
            assertTrue(response.body().contains("\"path\":\"/api/email/validate\""));
            assertFalse(response.body().contains("test-sentinel-secret"));
        }
        assertEquals(0, repository.count());
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

    private void assertDnsResponse(Outcome outcome, Reason reason, String domain, Boolean mxFound,
                                   String message) throws Exception {
        String email = "a@" + domain;
        when(dnsLookup.lookup(domain)).thenReturn(observation(outcome, reason, domain));
        HttpResponse<String> response = sendValidation(email);
        assertEquals(200, response.statusCode());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(7, body.size());
        assertEquals(email, body.path("email").asText());
        assertEquals(domain, body.path("domain").asText());
        assertTrue(body.path("formatValid").asBoolean());
        assertEquals("UNVERIFIED", body.path("status").asText());
        assertEquals(mxFound, body.path("mxRecordFound").isNull() ? null : body.path("mxRecordFound").booleanValue());
        assertTrue(body.path("smtpVerified").isNull());
        assertEquals(message, body.path("message").asText());

        assertEquals(1, repository.count());
        EmailValidationHistory history = repository.findAll().getFirst();
        assertEquals(email, history.getEmail());
        assertEquals(domain, history.getDomain());
        assertTrue(history.getFormatValid());
        assertEquals("UNVERIFIED", history.getStatus());
        assertEquals(mxFound, history.getMxRecordFound());
        assertNull(history.getSmtpVerified());
        assertEquals(message, history.getMessage());
        verify(dnsLookup).lookup(domain);
        repository.deleteAll();
    }

    private static DnsObservation observation(Outcome outcome, Reason reason, String domain) {
        List<DnsObservation.MxHost> mxHosts = switch (reason) {
            case MX_PRESENT, MX_TARGET_TEMPORARY_FAILURE, MX_TARGET_NO_ADDRESS, MX_TARGET_UNCERTAIN,
                    DNS_QUERY_BUDGET_EXCEEDED -> List.of(new DnsObservation.MxHost(10, "mx1.example.test"));
            case MULTIPLE_MX, MX_TARGET_PARTIAL -> List.of(new DnsObservation.MxHost(10, "mx1.example.test"),
                    new DnsObservation.MxHost(20, "mx2.example.test"));
            default -> List.of();
        };
        return new DnsObservation(outcome, reason, domain, mxHosts,
                reason == Reason.DNS_TEMPORARY_FAILURE || reason == Reason.MX_TARGET_TEMPORARY_FAILURE
                        || reason == Reason.MX_TARGET_PARTIAL, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static String longTestDomain(int thirdLabelLength) {
        return "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(thirdLabelLength) + ".test";
    }

    private HttpResponse<String> sendValidation(String email) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/email/validate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("email", email))))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

}

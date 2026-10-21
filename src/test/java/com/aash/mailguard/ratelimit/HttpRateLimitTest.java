package com.aash.mailguard.ratelimit;

import com.aash.mailguard.controller.EmailValidationHistoryController;
import com.aash.mailguard.controller.JobController;
import com.aash.mailguard.service.EmailValidationHistoryService;
import com.aash.mailguard.delivery.DeliveryController;
import com.aash.mailguard.imports.BulkImportController;
import com.aash.mailguard.imports.BulkImportStore;
import com.aash.mailguard.imports.CsvImportService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpRateLimitTest {
    private static final String SECRET = "synthetic-identity-secret-32-characters";
    private static final String KEY = "synthetic-api-key";
    private final RedisTokenBucket redis = mock(RedisTokenBucket.class);
    private final HttpRateLimit limiter = new HttpRateLimit(settings(true, 2, 1), redis,
            new SimpleMeterRegistry(), new ObjectMapper(), KEY);

    static RateLimitSettings settings(boolean enabled, int global, int fallback) {
        return new RateLimitSettings(enabled, SECRET, "fixture", 60,
                2, global, fallback, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2);
    }

    private static HandlerMethod handler(Class<?> type, String name) {
        Method method = java.util.Arrays.stream(type.getMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        return new HandlerMethod(mock(type), method);
    }

    private static MockHttpServletRequest request(String path, String peer, boolean authenticated) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(peer);
        if (authenticated) request.addHeader("X-Job-API-Key", KEY);
        request.addHeader("X-Forwarded-For", "203.0.113.123");
        return request;
    }

    private static RedisTokenBucket.Admission reply(boolean global, boolean peer, long delay) {
        return new RedisTokenBucket.Admission(
                new RedisTokenBucket.Result(global, global ? 1 : 0, delay),
                new RedisTokenBucket.Result(peer, peer ? 1 : 0, delay));
    }

    @Test
    void automaticOptionsKeepsAllowHeaderWithoutChargingQuota() throws Exception {
        var controller = new EmailValidationHistoryController(mock(EmailValidationHistoryService.class), KEY);
        var enabled = MockMvcBuilders.standaloneSetup(controller).addInterceptors(limiter).build();
        var disabled = MockMvcBuilders.standaloneSetup(controller).build();
        disabled.perform(options("/api/email/test")).andExpect(status().isOk())
                .andExpect(header().string("Allow", "POST,OPTIONS"));
        enabled.perform(options("/api/email/test")).andExpect(status().isOk())
                .andExpect(header().string("Allow", "POST,OPTIONS"));
        verifyNoInteractions(redis);
    }

    @Test
    void applicationOptionsAndUnknownHandlersStillFailClosed() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new UnmappedController()).addInterceptors(limiter).build();
        mvc.perform(options("/api/custom")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/custom")).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(redis);

        MockHttpServletResponse admin = new MockHttpServletResponse();
        assertFalse(limiter.preHandle(request("/api/jobs/id", "192.0.2.4", false), admin,
                handler(JobController.class, "get")));
        assertEquals(401, admin.getStatus());
    }

    @Test
    void globalRejectionKeepsPeerQuotaAndReturnsValidHeaders() throws Exception {
        when(redis.takePublic(contains("public_global:all"), eq(2), anyString(), eq(2), eq(60)))
                .thenReturn(reply(false, true, 1501));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(limiter.preHandle(request("/api/email/validate", "192.0.2.1", false), response,
                handler(EmailValidationHistoryController.class, "validate")));
        assertEquals(429, response.getStatus());
        assertEquals("2", response.getHeader("Retry-After"));
        assertEquals("2", response.getHeader("RateLimit-Limit"));
        assertEquals("0", response.getHeader("RateLimit-Remaining"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getContentAsString().contains("\"status\":429"));
        verify(redis).takePublic(anyString(), anyInt(), anyString(), anyInt(), anyInt());
        verify(redis, never()).take(anyString(), anyInt(), anyInt());
    }

    @Test
    void publicKeyUsesSocketPeerNotForwardedHeaders() throws Exception {
        when(redis.takePublic(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(reply(true, true, 250));
        assertTrue(limiter.preHandle(request("/api/email/test", "192.0.2.1", false),
                new MockHttpServletResponse(), handler(EmailValidationHistoryController.class, "test")));
        var spoofed = request("/api/email/test", "192.0.2.1", false);
        spoofed.removeHeader("X-Forwarded-For");
        spoofed.addHeader("X-Forwarded-For", "198.51.100.45");
        assertTrue(limiter.preHandle(spoofed,
                new MockHttpServletResponse(), handler(EmailValidationHistoryController.class, "test")));
        var keys = mockingDetails(redis).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("takePublic"))
                .map(call -> (String) call.getArgument(2)).toList();
        assertEquals(keys.get(0), keys.get(1));
        assertFalse(keys.get(0).contains("192.0.2.1"));
        assertFalse(keys.get(0).contains("203.0.113.123"));
    }

    @Test
    void unauthorizedUploadCannotContactBackendAndExportFailsClosedOnOutage() throws Exception {
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(limiter.preHandle(request("/api/imports", "192.0.2.4", false), denied,
                handler(BulkImportController.class, "create")));
        assertEquals(401, denied.getStatus());
        MockHttpServletRequest invalidKey = request("/api/email/validate", "192.0.2.4", false);
        invalidKey.addHeader("X-Job-API-Key", "wrong");
        assertFalse(limiter.preHandle(invalidKey, new MockHttpServletResponse(),
                handler(EmailValidationHistoryController.class, "validate")));
        verifyNoInteractions(redis);
        when(redis.take(anyString(), anyInt(), anyInt()))
                .thenThrow(new QueryTimeoutException("synthetic timeout"));
        MockHttpServletResponse outage = new MockHttpServletResponse();
        assertFalse(limiter.preHandle(request("/api/imports/id/export", "192.0.2.4", true), outage,
                handler(BulkImportController.class, "export")));
        assertEquals(503, outage.getStatus());
        assertEquals("no-store", outage.getHeader("Cache-Control"));
        assertFalse(outage.getContentAsString().contains("/id/"));
    }

    @Test
    void authorizedUploadAndExportUseFixedAdminKeys() throws Exception {
        when(redis.take(anyString(), anyInt(), anyInt()))
                .thenReturn(new RedisTokenBucket.Result(true, 1, 1500));
        assertTrue(limiter.preHandle(request("/api/imports", "192.0.2.4", true),
                new MockHttpServletResponse(), handler(BulkImportController.class, "create")));
        assertTrue(limiter.preHandle(request("/api/imports/random-id/export", "192.0.2.5", true),
                new MockHttpServletResponse(), handler(BulkImportController.class, "export")));
        var keys = mockingDetails(redis).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("take"))
                .map(call -> (String) call.getArgument(0)).toList();
        assertEquals("fixture:http-limit:v1:import_write:admin", keys.get(0));
        assertEquals("fixture:http-limit:v1:export:admin", keys.get(1));
    }

    @Test
    void exhaustedUploadQuotaRejectsBeforeParsingOrStagingMultipart() throws Exception {
        BulkImportStore store = mock(BulkImportStore.class);
        CsvImportService csv = mock(CsvImportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new BulkImportController(store, csv, KEY, 500))
                .addInterceptors(limiter).build();
        when(redis.take(contains("import_write:admin"), eq(2), eq(60)))
                .thenReturn(new RedisTokenBucket.Result(false, 0, 1200));
        mvc.perform(post("/api/imports").header("X-Job-API-Key", KEY)
                        .contentType(MediaType.MULTIPART_FORM_DATA).content("invalid multipart body"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(429));
        verifyNoInteractions(store, csv);
    }

    @Test
    void localFallbackCannotBeResetByIntermittentHealthyOrRejectedAdmission() throws Exception {
        when(redis.takePublic(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenThrow(new QueryTimeoutException("synthetic timeout"))
                .thenThrow(new QueryTimeoutException("synthetic timeout"))
                .thenReturn(reply(true, true, 500))
                .thenThrow(new QueryTimeoutException("synthetic timeout"))
                .thenReturn(reply(false, true, 500))
                .thenThrow(new QueryTimeoutException("synthetic timeout"))
                .thenThrow(new QueryTimeoutException("synthetic timeout"));
        HandlerMethod validation = handler(EmailValidationHistoryController.class, "validate");
        HandlerMethod test = handler(EmailValidationHistoryController.class, "test");
        assertTrue(limiter.preHandle(request("/api/email/validate", "192.0.2.4", false),
                new MockHttpServletResponse(), validation));
        MockHttpServletResponse firstDenial = new MockHttpServletResponse();
        assertFalse(limiter.preHandle(request("/api/email/test", "192.0.2.5", false), firstDenial, test));
        assertEquals(429, firstDenial.getStatus());
        assertTrue(limiter.preHandle(request("/api/email/test", "192.0.2.6", false),
                new MockHttpServletResponse(), test));
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse rejected = new MockHttpServletResponse();
            assertFalse(limiter.preHandle(request("/api/email/test", "192.0.2.7", false),
                    rejected, test));
            assertEquals(429, rejected.getStatus());
        }
        org.springframework.test.util.ReflectionTestUtils.setField(limiter, "fallbackStarted",
                System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(61));
        assertTrue(limiter.preHandle(request("/api/email/test", "192.0.2.7", false),
                new MockHttpServletResponse(), test));
    }

    @Test
    void administrativeSuccessDoesNotResetLocalOutageQuota() throws Exception {
        when(redis.takePublic(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenThrow(new QueryTimeoutException("synthetic timeout"));
        when(redis.take(contains(":job_read:admin"), eq(2), eq(60)))
                .thenReturn(new RedisTokenBucket.Result(true, 1, 500));
        HandlerMethod validation = handler(EmailValidationHistoryController.class, "validate");
        assertTrue(limiter.preHandle(request("/api/email/validate", "192.0.2.4", false),
                new MockHttpServletResponse(), validation));
        assertTrue(limiter.preHandle(request("/api/jobs/id", "192.0.2.4", true),
                new MockHttpServletResponse(), handler(JobController.class, "get")));
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(limiter.preHandle(request("/api/email/validate", "192.0.2.4", false),
                denied, validation));
        assertEquals(429, denied.getStatus());
        verify(redis, times(2)).takePublic(anyString(), anyInt(), anyString(), anyInt(), anyInt());
    }

    @Test
    void rejectsBeforeBodyParsingAndLeavesSuccessfulBodyUntouched() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                new EmailValidationHistoryController(mock(EmailValidationHistoryService.class), KEY))
                .addInterceptors(limiter).build();
        when(redis.takePublic(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(reply(false, true, 1200));
        mvc.perform(post("/api/email/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.status").value(429));
        reset(redis);
        when(redis.takePublic(anyString(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(reply(true, true, 500));
        mvc.perform(post("/api/email/test")).andExpect(status().isOk())
                .andExpect(content().string("MailGuard API Working"));
    }

    @Test
    void everyMappedApiMethodHasPolicyAndWorkersHaveNoHttpAdmission() throws Exception {
        for (Class<?> type : new Class<?>[] {EmailValidationHistoryController.class, JobController.class,
                BulkImportController.class, DeliveryController.class}) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
                        || method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
                        || method.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class)) {
                    assertNotNull(HttpRateLimit.policy(new HandlerMethod(mock(type), method)),
                            type.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertTrue(limiter.preHandle(request("/internal/worker", "192.0.2.8", false),
                new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(redis);
    }

    @Test
    void rejectsInvalidEnabledConfigurationButDisabledNeedsNoValkeySecret() {
        assertFalse(new RateLimitSettings(false, "", "local", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).enabled());
        assertThrows(IllegalArgumentException.class, () -> new RateLimitSettings(true, "short", "local", 60,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitSettings(true, SECRET, "unsafe:namespace", 60,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitSettings(true, SECRET, "local", 60,
                1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitSettings(true, SECRET, "local", 3601,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitSettings(true, SECRET, "local", 60,
                1, 100001, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
        var disabled = new HttpRateLimit(new RateLimitSettings(false, "", "local", 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), redis,
                new SimpleMeterRegistry(), new ObjectMapper(), "");
        assertDoesNotThrow(() -> assertTrue(disabled.preHandle(
                request("/api/email/test", "192.0.2.1", false), new MockHttpServletResponse(),
                handler(EmailValidationHistoryController.class, "test"))));
        verifyNoInteractions(redis);
    }
    @RestController
    static class UnmappedController {
        @RequestMapping(path = "/api/custom", method = RequestMethod.OPTIONS)
        String options() { return "custom"; }

        @GetMapping("/api/custom")
        String get() { return "custom"; }
    }

}

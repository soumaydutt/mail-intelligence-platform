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

    @RestController
    static class UnmappedController {
        @RequestMapping(path = "/api/custom", method = RequestMethod.OPTIONS)
        String options() { return "custom"; }

        @GetMapping("/api/custom")
        String get() { return "custom"; }
    }

}

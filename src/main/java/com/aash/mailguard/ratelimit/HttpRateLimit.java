package com.aash.mailguard.ratelimit;

import com.aash.mailguard.controller.EmailValidationHistoryController;
import com.aash.mailguard.controller.JobController;
import com.aash.mailguard.delivery.DeliveryController;
import com.aash.mailguard.exception.ErrorResponse;
import com.aash.mailguard.imports.BulkImportController;
import com.aash.mailguard.ratelimit.RateLimitSettings.Policy;
import com.aash.mailguard.ratelimit.RedisTokenBucket.Admission;
import com.aash.mailguard.ratelimit.RedisTokenBucket.Result;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

@Component
public class HttpRateLimit implements HandlerInterceptor, WebMvcConfigurer {
    private final RateLimitSettings settings;
    private final RedisTokenBucket bucket;
    private final MeterRegistry metrics;
    private final ObjectMapper json;
    private final byte[] apiKey;
    private final SecretKeySpec hmacKey;
    // shortcut: per-instance outage budget, replace only if public outage admission needs a cluster-wide guarantee.
    private long fallbackStarted;
    private int fallbackUsed;

    public HttpRateLimit(RateLimitSettings settings, RedisTokenBucket bucket, MeterRegistry metrics,
            ObjectMapper json, @Value("${jobs.api-key:}") String apiKey) {
        this.settings = settings;
        this.bucket = bucket;
        this.metrics = metrics;
        this.json = json;
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        if (settings.enabled() && (this.apiKey.length == 0 || this.apiKey.length > 256)) {
            throw new IllegalArgumentException("Enabled HTTP rate limiting requires a bounded administrative API key");
        }
        this.hmacKey = settings.enabled() ? new SecretKeySpec(settings.secret(), "HmacSHA256") : null;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (settings.enabled()) {
            registry.addInterceptor(this).addPathPatterns("/api/**");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!settings.enabled() || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        // Spring MVC's private synthetic OPTIONS handler is not an application endpoint.
        if ("OPTIONS".equals(request.getMethod()) && method.getBeanType().getName().equals(
                RequestMappingInfoHandlerMapping.class.getName() + "$HttpOptionsHandler")) {
            return true;
        }
        Policy policy = policy(method);
        if (policy == null) {
            reject(request, response, 503, "Rate limiting unavailable", 1);
            return false; // Newly mapped /api handlers cannot silently bypass admission.
        }
        boolean publicRoute = policy == Policy.PUBLIC || policy == Policy.TEST;
        String supplied = request.getHeader("X-Job-API-Key");
        if ((!publicRoute || policy == Policy.PUBLIC && supplied != null) && !authenticated(supplied)) {
            count(policy, "unauthorized");
            reject(request, response, 401, "Unauthorized", 0);
            return false;
        }
        try {
            Result result;
            if (publicRoute) {
                Admission admission = takePublic(policy, request.getRemoteAddr());
                Result global = admission.global();
                if (!global.allowed()) {
                    count(policy, "rejected");
                    headers(response, settings.capacity(Policy.PUBLIC_GLOBAL), global);
                    reject(request, response, 429, "Rate limit reached", global.delaySeconds());
                    return false;
                }
                result = admission.peer();
            } else {
                result = take(policy, "admin");
            }
            headers(response, settings.capacity(policy), result);
            if (!result.allowed()) {
                count(policy, "rejected");
                reject(request, response, 429, "Rate limit reached", result.delaySeconds());
                return false;
            }
            count(policy, "allowed");
            return true;
        } catch (org.springframework.dao.DataAccessException | IllegalStateException ex) {
            count(policy, "backend_error");
            if (!publicRoute) {
                reject(request, response, 503, "Rate limiting unavailable", 0);
                return false;
            }
            Result local = fallback();
            headers(response, settings.fallbackCapacity(), local);
            if (!local.allowed()) {
                count(policy, "fallback_rejected");
                reject(request, response, 429, "Rate limit reached", local.delaySeconds());
                return false;
            }
            count(policy, "fallback_allowed");
            return true;
        }
    }

    private Result take(Policy policy, String identity) {
        long started = System.nanoTime();
        try {
            return bucket.take(settings.prefix() + policy.name().toLowerCase(java.util.Locale.ROOT) + ':' + identity,
                    settings.capacity(policy), settings.windowSeconds());
        } finally {
            metrics.timer("mailguard.http.rate.backend", "policy", policy.name())
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private Admission takePublic(Policy policy, String address) {
        long started = System.nanoTime();
        try {
            return bucket.takePublic(settings.prefix() + "public_global:all",
                    settings.capacity(Policy.PUBLIC_GLOBAL),
                    settings.prefix() + policy.name().toLowerCase(java.util.Locale.ROOT) + ':' + peer(address),
                    settings.capacity(policy), settings.windowSeconds());
        } finally {
            metrics.timer("mailguard.http.rate.backend", "policy", policy.name())
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private synchronized Result fallback() {
        long now = System.nanoTime();
        long window = TimeUnit.SECONDS.toNanos(settings.windowSeconds());
        if (fallbackStarted == 0 || now - fallbackStarted >= window) {
            fallbackStarted = now;
            fallbackUsed = 0;
        }
        long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(window - (now - fallbackStarted)));
        if (fallbackUsed >= settings.fallbackCapacity()) {
            return new Result(false, 0, remainingMillis);
        }
        return new Result(true, settings.fallbackCapacity() - ++fallbackUsed, remainingMillis);
    }

    private boolean authenticated(String supplied) {
        if (apiKey.length == 0 || supplied == null || supplied.length() > 256) {
            return false;
        }
        return MessageDigest.isEqual(apiKey, supplied.getBytes(StandardCharsets.UTF_8));
    }

    private String peer(String address) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(address.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC unavailable", ex);
        }
    }

    private void headers(HttpServletResponse response, long limit, Result result) {
        response.setHeader("RateLimit-Limit", Long.toString(limit));
        response.setHeader("RateLimit-Remaining", Long.toString(result.remaining()));
        response.setHeader("RateLimit-Reset", Long.toString(result.delaySeconds()));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, int status,
            String message, long retry) throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == 429) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retry));
        }
        response.setContentType("application/json");
        ErrorResponse error = new ErrorResponse();
        error.setTimestamp(LocalDateTime.now().toString());
        error.setStatus(status);
        error.setMessage(message);
        // Never reflect arbitrary URI segments or query strings into the error body.
        error.setPath(publicPath(request.getServletPath()));
        json.writeValue(response.getOutputStream(), error);
    }

    private static String publicPath(String path) {
        if (path.equals("/api/email/validate") || path.equals("/api/email/test")) {
            return path;
        }
        if (path.startsWith("/api/jobs")) return "/api/jobs";
        if (path.startsWith("/api/imports")) return "/api/imports";
        if (path.startsWith("/api/delivery-events")) return "/api/delivery-events";
        if (path.equals("/api/delivery-evidence/lookup")) return path;
        if (path.startsWith("/api/suppressions")) return "/api/suppressions";
        return "/";
    }

    private void count(Policy policy, String outcome) {
        metrics.counter("mailguard.http.rate.requests", "policy", policy.name(), "result", outcome).increment();
    }

    public static Policy policy(HandlerMethod method) {
        Class<?> type = method.getBeanType();
        String name = method.getMethod().getName();
        if (type == EmailValidationHistoryController.class) {
            return switch (name) {
                case "validate" -> Policy.PUBLIC;
                case "test" -> Policy.TEST;
                default -> null;
            };
        }
        if (type == JobController.class) {
            return switch (name) {
                case "submit", "cancel" -> Policy.JOB_WRITE;
                case "get" -> Policy.JOB_READ;
                default -> null;
            };
        }
        if (type == BulkImportController.class) {
            return switch (name) {
                case "create", "cancel" -> Policy.IMPORT_WRITE;
                case "get", "results" -> Policy.IMPORT_READ;
                case "export" -> Policy.EXPORT;
                default -> null;
            };
        }
        if (type == DeliveryController.class) {
            return switch (name) {
                case "ingest", "revoke" -> Policy.EVENT_WRITE;
                case "lookup" -> Policy.EVIDENCE_READ;
                case "suppress", "lift" -> Policy.SUPPRESSION_WRITE;
                case "suppressions" -> Policy.SUPPRESSION_READ;
                default -> null;
            };
        }
        return null;
    }
}

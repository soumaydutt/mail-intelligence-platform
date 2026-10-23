package com.aash.mailguard;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class RequestObservability extends OncePerRequestFilter {
    private final MeterRegistry meters;

    RequestObservability(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", id);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", id)) {
            try {
                chain.doFilter(request, response);
            } finally {
                String route = route(request.getServletPath());
                if (route != null) {
                    meters.counter("mailguard.http.requests", "route", route,
                            "status_class", response.getStatus() / 100 + "xx").increment();
                }
            }
        }
    }

    static String route(String path) {
        if (path.equals("/api/email/validate")) return "validation";
        if (path.equals("/api/email/test")) return "diagnostic";
        if (path.equals("/api/jobs") || path.startsWith("/api/jobs/")) return "jobs";
        if (path.equals("/api/imports") || path.startsWith("/api/imports/")) return "imports";
        if (path.equals("/api/delivery-events") || path.startsWith("/api/delivery-events/")) return "delivery_events";
        if (path.equals("/api/delivery-evidence/lookup")) return "delivery_evidence";
        if (path.equals("/api/suppressions") || path.startsWith("/api/suppressions/")) return "suppressions";
        return path.startsWith("/api/") ? "other" : null;
    }
}

package com.aash.mailguard;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class RequestObservabilityTest {
    @Test
    void untrustedPathsAndRequestIdsNeverBecomeMetricTags() throws Exception {
        var meters = new SimpleMeterRegistry();
        var filter = new RequestObservability(meters);
        for (int i = 0; i < 100; i++) {
            var request = new MockHttpServletRequest("GET", "/api/jobs/private-" + i);
            request.setServletPath("/api/jobs/private-" + i);
            request.addHeader("X-Request-ID", "attacker-" + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> response.setStatus(401));
            assertNotEquals("attacker-" + i, response.getHeader("X-Request-ID"));
            assertNotNull(response.getHeader("X-Request-ID"));
        }
        assertEquals(100, meters.get("mailguard.http.requests")
                .tags("route", "jobs", "status_class", "4xx").counter().count());
        assertEquals(1, meters.find("mailguard.http.requests").counters().size());
        assertEquals("other", RequestObservability.route("/api/arbitrary-untrusted-path"));
        assertNull(RequestObservability.route("/actuator/prometheus"));
    }
}

package com.aash.mailguard.dns;

import com.aash.mailguard.dns.DnsObservation.MxHost;
import com.aash.mailguard.dns.DnsObservation.Reason;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.xbill.DNS.Name;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

final class DnsObservationCache {
    record Result(DnsObservation observation, long positiveTtlMillis, long negativeTtlMillis) { }

    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final String namespace;
    private final int positiveCap;
    private final int negativeCap;
    private final int maxBytes;
    private final int maxInflight;
    private final int waitMillis;
    private final Map<String, CompletableFuture<DnsObservation>> inflight = new HashMap<>();
    private final AtomicInteger waiting = new AtomicInteger();

    DnsObservationCache(StringRedisTemplate redis, MeterRegistry metrics, String namespace,
                        int positiveCap, int negativeCap, int maxBytes, int maxInflight, int waitMillis) {
        validateSettings(namespace, positiveCap, negativeCap, maxBytes, maxInflight, waitMillis);
        if (redis == null || metrics == null) {
            throw new IllegalArgumentException("Missing DNS cache dependencies");
        }
        this.redis = redis;
        this.metrics = metrics;
        this.namespace = namespace;
        this.positiveCap = positiveCap;
        this.negativeCap = negativeCap;
        this.maxBytes = maxBytes;
        this.maxInflight = maxInflight;
        this.waitMillis = waitMillis;
    }
    static void validateSettings(String namespace, int positiveCap, int negativeCap, int maxBytes,
                                 int maxInflight, int waitMillis) {
        if (namespace == null || !namespace.matches("[A-Za-z0-9_-]{1,32}")
                || positiveCap < 1 || positiveCap > 86400 || negativeCap < 1 || negativeCap > 86400
                || maxBytes < 128 || maxBytes > 65536 || maxInflight < 1 || maxInflight > 4096
                || waitMillis < 1 || waitMillis > 30000) {
            throw new IllegalArgumentException("Invalid DNS cache settings");
        }
    }

    DnsObservation lookup(String domain, Supplier<Result> lookup) {
        String key = key(domain);
        long start = System.nanoTime();
        try {
            try {
                String payload = redis.opsForValue().get(key, 0, maxBytes);
                if (payload != null) {
                    DnsObservation hit = decode(domain, payload);
                    if (hit != null) {
                        count("hit");
                        return hit;
                    }
                    count("corrupt");
                }
            } catch (RuntimeException e) {
                count("read_error");
                return lookup.get().observation();
            }
            CompletableFuture<DnsObservation> future;
            boolean leader;
            synchronized (inflight) {
                future = inflight.get(key);
                leader = future == null && inflight.size() < maxInflight;
                if (leader) {
                    future = new CompletableFuture<>();
                    inflight.put(key, future);
                }
            }
            if (leader) {
                try {
                    Result result = lookup.get();
                    long ttlMillis = switch (result.observation().reason()) {
                        case MX_PRESENT, MULTIPLE_MX, NULL_MX ->
                                Math.min(result.positiveTtlMillis(), positiveCap * 1000L);
                        case IMPLICIT_MX ->
                                Math.min(result.negativeTtlMillis(), positiveCap * 1000L);
                        case DOMAIN_NOT_FOUND, NO_MAIL_ROUTE, MX_TARGET_NO_ADDRESS ->
                                Math.min(result.negativeTtlMillis(), negativeCap * 1000L);
                        default -> 0;
                    };
                    if (ttlMillis > 0 && !result.observation().retryable()) {
                        String payload = encode(result.observation());
                        if (payload.length() <= maxBytes) {
                            try {
                                redis.opsForValue().set(key, payload, Duration.ofMillis(ttlMillis));
                                count("write");
                            } catch (RuntimeException e) {
                                count("write_error");
                            }
                        } else {
                            count("oversize");
                        }
                    }
                    future.complete(result.observation());
                    count("miss");
                    return result.observation();
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                    throw t;
                } finally {
                    synchronized (inflight) {
                        inflight.remove(key, future);
                    }
                }
            }
            if (future == null) {
                count("capacity");
                return lookup.get().observation();
            }
            if (waiting.incrementAndGet() > maxInflight) {
                waiting.decrementAndGet();
                count("wait_capacity");
                return lookup.get().observation();
            }
            try {
                try {
                    DnsObservation shared = future.get(waitMillis, TimeUnit.MILLISECONDS);
                    count("shared");
                    return shared;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    count("interrupted");
                } catch (ExecutionException | TimeoutException | CancellationException e) {
                    count("wait_timeout_or_error");
                }
            } finally {
                waiting.decrementAndGet();
            }
            return lookup.get().observation();
        } finally {
            metrics.timer("dns.cache.lookup").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    String key(String domain) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("dns:v1:" + namespace + ":" + domain).getBytes(StandardCharsets.US_ASCII));
            return "dns:" + namespace + ":v1:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void count(String result) {
        metrics.counter("dns.cache.operations", "result", result).increment();
    }

    private String encode(DnsObservation observation) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(1);
            out.writeUTF(observation.domain());
            out.writeUTF(observation.reason().name());
            out.writeLong(observation.observedAt().getEpochSecond());
            out.writeInt(observation.observedAt().getNano());
            out.writeInt(observation.mxHosts().size());
            for (MxHost host : observation.mxHosts()) {
                out.writeInt(host.preference());
                out.writeUTF(host.host());
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private DnsObservation decode(String domain, String payload) {
        if (payload.length() > maxBytes) {
            return null;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(payload)));
            if (in.readUnsignedByte() != 1 || !domain.equals(in.readUTF())) {
                return null;
            }
            Reason reason = Reason.valueOf(in.readUTF());
            if (reason != Reason.MX_PRESENT && reason != Reason.MULTIPLE_MX && reason != Reason.IMPLICIT_MX
                    && reason != Reason.NULL_MX && reason != Reason.DOMAIN_NOT_FOUND && reason != Reason.NO_MAIL_ROUTE
                    && reason != Reason.MX_TARGET_NO_ADDRESS) {
                return null;
            }
            Instant observed = Instant.ofEpochSecond(in.readLong(), in.readInt());
            int count = in.readInt();
            if (count < 0 || count > 32 || (count == 0 && (reason == Reason.MX_PRESENT
                    || reason == Reason.MULTIPLE_MX || reason == Reason.MX_TARGET_NO_ADDRESS))
                    || (count != 0 && reason != Reason.MX_PRESENT
                    && reason != Reason.MULTIPLE_MX && reason != Reason.MX_TARGET_NO_ADDRESS)) {
                return null;
            }
            List<MxHost> hosts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int priority = in.readInt();
                String host = in.readUTF();
                if (priority < 0 || priority > 65535 || host.length() > 255) {
                    return null;
                }
                Name name = Name.fromString(host);
                if (!name.isAbsolute() || name.equals(Name.root)) {
                    return null;
                }
                hosts.add(new MxHost(priority, host));
            }
            if (in.available() != 0) {
                return null;
            }
            DnsObservation.Outcome outcome = switch (reason) {
                case MX_PRESENT, MULTIPLE_MX, IMPLICIT_MX -> DnsObservation.Outcome.MAIL_ROUTE;
                case NULL_MX -> DnsObservation.Outcome.MAIL_NOT_ACCEPTED;
                default -> DnsObservation.Outcome.NO_MAIL_ROUTE;
            };
            return new DnsObservation(outcome, reason, domain, hosts, false, observed);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}

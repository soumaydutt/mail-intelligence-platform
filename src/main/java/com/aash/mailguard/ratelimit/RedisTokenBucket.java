package com.aash.mailguard.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisTokenBucket {
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local now = redis.call('TIME')
            local ms = now[1] * 1000 + math.floor(now[2] / 1000)
            local window = tonumber(ARGV[2])
            local function refill(key, capacity)
                local entry = redis.call('HMGET', key, 'tokens', 'at')
                local full = capacity * window
                if entry[1] and entry[2] then
                    local elapsed = math.max(0, math.min(window, ms - tonumber(entry[2])))
                    return math.min(full, tonumber(entry[1]) + elapsed * capacity)
                end
                return full
            end
            local firstCapacity = tonumber(ARGV[1])
            local first = refill(KEYS[1], firstCapacity)
            local secondCapacity = KEYS[2] and tonumber(ARGV[3])
            local second = secondCapacity and refill(KEYS[2], secondCapacity)
            local firstReady = first >= window
            local secondReady = not second or second >= window
            if firstReady and secondReady then
                first = first - window
                redis.call('HSET', KEYS[1], 'tokens', first, 'at', ms)
                redis.call('PEXPIRE', KEYS[1], window * 2)
                if second then
                    second = second - window
                    redis.call('HSET', KEYS[2], 'tokens', second, 'at', ms)
                    redis.call('PEXPIRE', KEYS[2], window * 2)
                end
            elseif not second then
                redis.call('HSET', KEYS[1], 'tokens', first, 'at', ms)
                redis.call('PEXPIRE', KEYS[1], window * 2)
            end
            local firstMissing = firstReady and (firstCapacity * window - first) or (window - first)
            local firstDelay = math.ceil(firstMissing / firstCapacity)
            if second then
                local secondMissing = secondReady and (secondCapacity * window - second) or (window - second)
                return { firstReady and 1 or 0, math.floor(first / window), firstDelay,
                         secondReady and 1 or 0, math.floor(second / window),
                         math.ceil(secondMissing / secondCapacity) }
            end
            return { firstReady and 1 or 0, math.floor(first / window), firstDelay }
            """, List.class);

    private final StringRedisTemplate redis;

    public RedisTokenBucket(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Result take(String key, int capacity, int windowSeconds) {
        List<?> answer = redis.execute(SCRIPT, List.of(key), Integer.toString(capacity),
                Long.toString(windowSeconds * 1000L));
        if (answer == null || answer.size() != 3) {
            throw new IllegalStateException("Unexpected Valkey bucket response");
        }
        return result(answer, 0);
    }

    public Admission takePublic(String globalKey, int globalCapacity, String peerKey,
            int peerCapacity, int windowSeconds) {
        List<?> answer = redis.execute(SCRIPT, List.of(globalKey, peerKey),
                Integer.toString(globalCapacity), Long.toString(windowSeconds * 1000L),
                Integer.toString(peerCapacity));
        if (answer == null || answer.size() != 6) {
            throw new IllegalStateException("Unexpected Valkey bucket response");
        }
        return new Admission(result(answer, 0), result(answer, 3));
    }

    private static Result result(List<?> answer, int offset) {
        if (!(answer.get(offset) instanceof Number allowed)
                || !(answer.get(offset + 1) instanceof Number remaining)
                || !(answer.get(offset + 2) instanceof Number delay)) {
            throw new IllegalStateException("Unexpected Valkey bucket response");
        }
        return new Result(allowed.longValue() == 1, remaining.longValue(), delay.longValue());
    }

    public record Admission(Result global, Result peer) { }

    public record Result(boolean allowed, long remaining, long delayMillis) {
        public long delaySeconds() { return Math.max(1, (delayMillis + 999) / 1000); }
    }
}

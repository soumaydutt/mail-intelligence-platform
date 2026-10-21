package com.aash.mailguard.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = false)
class RedisTokenBucketTest {
    @Container
    static final GenericContainer<?> valkey = new GenericContainer<>("valkey/valkey:8-alpine")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connection() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(valkey.getHost(), valkey.getMappedPort(6379));
        factory.afterPropertiesSet();
        return factory;
    }

    @Test
    void atomicBurstRetryRefillAndExpiration() throws Exception {
        LettuceConnectionFactory factory = connection();
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            RedisTokenBucket bucket = new RedisTokenBucket(redis);
            String key = "fixture:bucket:" + UUID.randomUUID();
            assertEquals(1, bucket.take(key, 2, 2).remaining());
            assertEquals(0, bucket.take(key, 2, 2).remaining());
            RedisTokenBucket.Result denied = bucket.take(key, 2, 2);
            assertFalse(denied.allowed());
            assertEquals(0, denied.remaining());
            assertTrue(denied.delayMillis() > 0 && denied.delayMillis() <= 1000);
            assertEquals(1, denied.delaySeconds());
            assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) > 0);
            redis.opsForHash().increment(key, "at", -1100L);
            assertTrue(bucket.take(key, 2, 2).allowed());
            assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) <= Duration.ofSeconds(4).toMillis());
        } finally {
            factory.destroy();
        }
    }

    @Test
    void independentClientsObserveSameAtomicBurst() throws Exception {
        LettuceConnectionFactory first = connection();
        LettuceConnectionFactory second = connection();
        try {
            RedisTokenBucket a = new RedisTokenBucket(new StringRedisTemplate(first));
            RedisTokenBucket b = new RedisTokenBucket(new StringRedisTemplate(second));
            String key = "fixture:concurrent:" + UUID.randomUUID();
            int requests = 32;
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(8)) {
                var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int n = 0; n < requests; n++) {
                    RedisTokenBucket client = n % 2 == 0 ? a : b;
                    tasks.add(executor.submit(() -> {
                        start.await();
                        return client.take(key, 5, 60).allowed();
                    }));
                }
                start.countDown();
                int accepted = 0;
                for (var task : tasks) if (task.get(15, TimeUnit.SECONDS)) accepted++;
                assertEquals(5, accepted);
            }
        } finally {
            first.destroy();
            second.destroy();
        }
    }

    @Test
    void rejectedPeerCannotDrainGlobalAndAnotherPeerCanUseRemainingBurst() {
        LettuceConnectionFactory factory = connection();
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            RedisTokenBucket bucket = new RedisTokenBucket(redis);
            String prefix = "fixture:paired:" + UUID.randomUUID();
            String global = prefix + ":global", abusive = prefix + ":abusive", other = prefix + ":other";
            assertTrue(bucket.takePublic(global, 5, abusive, 1, 60).peer().allowed());
            Object stored = redis.opsForHash().get(global, "tokens");
            for (int i = 0; i < 100; i++) {
                RedisTokenBucket.Admission denied = bucket.takePublic(global, 5, abusive, 1, 60);
                assertTrue(denied.global().allowed());
                assertFalse(denied.peer().allowed());
                assertEquals(4, denied.global().remaining());
                assertTrue(denied.peer().delaySeconds() >= 1);
            }
            assertEquals(stored, redis.opsForHash().get(global, "tokens"));
            for (int i = 3; i >= 0; i--) {
                RedisTokenBucket.Admission admitted = bucket.takePublic(global, 5, other, 5, 60);
                assertTrue(admitted.global().allowed());
                assertTrue(admitted.peer().allowed());
                assertEquals(i, admitted.global().remaining());
            }
            String newPeer = prefix + ":new";
            RedisTokenBucket.Admission denied = bucket.takePublic(global, 5, newPeer, 5, 60);
            assertFalse(denied.global().allowed());
            assertTrue(denied.global().delaySeconds() >= 1);
            assertFalse(redis.hasKey(newPeer), "Global rejection must not create peer identities");
            assertTrue(redis.getExpire(global, TimeUnit.MILLISECONDS) > 0);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void globalRejectionDoesNotSpendExistingPeerQuota() {
        LettuceConnectionFactory factory = connection();
        try {
            StringRedisTemplate redis = new StringRedisTemplate(factory);
            RedisTokenBucket bucket = new RedisTokenBucket(redis);
            String prefix = "fixture:paired:" + UUID.randomUUID();
            String global = prefix + ":global", a = prefix + ":a", b = prefix + ":b";
            assertTrue(bucket.takePublic(global, 2, a, 2, 60).peer().allowed());
            assertTrue(bucket.takePublic(global, 2, b, 2, 60).peer().allowed());
            Object stored = redis.opsForHash().get(b, "tokens");
            RedisTokenBucket.Admission denied = bucket.takePublic(global, 2, b, 2, 60);
            assertFalse(denied.global().allowed());
            assertTrue(denied.peer().allowed());
            assertEquals(stored, redis.opsForHash().get(b, "tokens"));
            redis.opsForHash().increment(global, "at", -61_000L);
            RedisTokenBucket.Admission recovered = bucket.takePublic(global, 2, b, 2, 60);
            assertTrue(recovered.global().allowed());
            assertTrue(recovered.peer().allowed());
            assertEquals(0, recovered.peer().remaining());
        } finally {
            factory.destroy();
        }
    }

    @Test
    void independentClientsAtomicallyEnforceBothBucketsUnderConcurrency() throws Exception {
        LettuceConnectionFactory first = connection();
        LettuceConnectionFactory second = connection();
        try {
            RedisTokenBucket a = new RedisTokenBucket(new StringRedisTemplate(first));
            RedisTokenBucket b = new RedisTokenBucket(new StringRedisTemplate(second));
            String prefix = "fixture:paired:" + UUID.randomUUID();
            int requests = 64;
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(16)) {
                var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int n = 0; n < requests; n++) {
                    int index = n;
                    tasks.add(executor.submit(() -> {
                        start.await();
                        RedisTokenBucket client = index % 2 == 0 ? a : b;
                        RedisTokenBucket.Admission result = client.takePublic(prefix + ":global", 7,
                                prefix + (index % 3 == 0 ? ":first" : ":second"),
                                index % 3 == 0 ? 3 : 5, 60);
                        return result.global().allowed() && result.peer().allowed();
                    }));
                }
                start.countDown();
                int admitted = 0;
                for (var task : tasks) if (task.get(15, TimeUnit.SECONDS)) admitted++;
                assertEquals(7, admitted);
            }
        } finally {
            first.destroy();
            second.destroy();
        }
    }
}

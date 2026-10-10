package tn.vas.security;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class RateLimiterTests {
    private static StringRedisTemplate deadRedis() {
        var cf = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6399),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
        cf.afterPropertiesSet();
        return new StringRedisTemplate(cf);
    }

    @Test
    void redisDownFallsBackToLocalCounterWithoutBlocking() {
        var metrics = new SimpleMeterRegistry();
        var limiter = new RateLimiter.Redis(deadRedis(), metrics);
        long t0 = System.nanoTime();
        assertTrue(limiter.allow("client:1", 3), "service disponible malgré Redis en panne");
        long first = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(first < 3000, "première requête : " + first + " ms");
        // les requêtes suivantes n'attendent plus Redis (pause de 10 s) et restent limitées localement
        long t1 = System.nanoTime();
        assertTrue(limiter.allow("client:1", 3));
        assertTrue(limiter.allow("client:1", 3));
        assertFalse(limiter.allow("client:1", 3), "quota local respecté : 4e requête refusée");
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 200, "plus d'attente sur Redis");
        assertTrue(limiter.allow("client:2", 3), "autre client indépendant");
        assertEquals(5.0, metrics.counter("vas.ratelimit.fallback").count(), "repli compté");
    }

    @Test
    void inMemoryLimiterWindowsPerMinute() {
        var l = new RateLimiter.InMemory();
        for (int i = 0; i < 5; i++) assertTrue(l.allow("k", 5));
        assertFalse(l.allow("k", 5));
    }
}

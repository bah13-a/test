package tn.vas.security;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

public interface RateLimiter {
    /** @return true si la requête est autorisée dans la minute courante. */
    boolean allow(String key, int perMinute);

    class InMemory implements RateLimiter {
        private final ConcurrentHashMap<String, long[]> w = new ConcurrentHashMap<>();

        @Override
        public boolean allow(String key, int perMinute) {
            long minute = System.currentTimeMillis() / 60000;
            long[] s = w.compute(key, (k, v) -> v == null || v[0] != minute ? new long[]{minute, 1} : new long[]{minute, v[1] + 1});
            return s[1] <= perMinute;
        }
    }

    class Redis implements RateLimiter {
        private final StringRedisTemplate redis;

        Redis(StringRedisTemplate redis) { this.redis = redis; }

        @Override
        public boolean allow(String key, int perMinute) {
            String k = "rl:" + key + ":" + System.currentTimeMillis() / 60000;
            Long n = redis.opsForValue().increment(k);
            if (n != null && n == 1L) redis.expire(k, Duration.ofSeconds(70));
            return n != null && n <= perMinute;
        }
    }

    @Configuration
    class Config {
        @Bean
        @ConditionalOnProperty(name = "vas.rate-limit", havingValue = "redis")
        RateLimiter redisLimiter(StringRedisTemplate redis) { return new Redis(redis); }

        @Bean
        @ConditionalOnProperty(name = "vas.rate-limit", havingValue = "memory", matchIfMissing = true)
        RateLimiter memoryLimiter() { return new InMemory(); }
    }
}

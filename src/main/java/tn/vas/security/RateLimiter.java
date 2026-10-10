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

    /**
     * Quotas partagés via Redis. Si Redis est injoignable : repli immédiat sur un compteur local par instance (le service API reste
     * disponible, avec une limitation moins précise), journalisé et compté ; Redis n'est ré-essayé qu'après 10 s pour ne pas
     * ajouter un délai de connexion à chaque requête.
     */
    class Redis implements RateLimiter {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Redis.class);
        private final StringRedisTemplate redis;
        private final InMemory fallback = new InMemory();
        private final io.micrometer.core.instrument.Counter fallbackCount;
        private volatile long skipRedisUntil;

        Redis(StringRedisTemplate redis, io.micrometer.core.instrument.MeterRegistry metrics) {
            this.redis = redis;
            this.fallbackCount = metrics.counter("vas.ratelimit.fallback");
        }

        @Override
        public boolean allow(String key, int perMinute) {
            if (System.currentTimeMillis() < skipRedisUntil) return local(key, perMinute);
            try {
                String k = "rl:" + key + ":" + System.currentTimeMillis() / 60000;
                Long n = redis.opsForValue().increment(k);
                if (n != null && n == 1L) redis.expire(k, Duration.ofSeconds(70));
                return n != null && n <= perMinute;
            } catch (RuntimeException e) {
                skipRedisUntil = System.currentTimeMillis() + 10_000;
                log.warn("Redis indisponible pour les quotas API : repli sur compteur local pendant 10 s ({})", e.getClass().getSimpleName());
                return local(key, perMinute);
            }
        }

        private boolean local(String key, int perMinute) {
            fallbackCount.increment();
            return fallback.allow(key, perMinute);
        }
    }

    @Configuration
    class Config {
        @Bean
        @ConditionalOnProperty(name = "vas.rate-limit", havingValue = "redis")
        RateLimiter redisLimiter(StringRedisTemplate redis, io.micrometer.core.instrument.MeterRegistry metrics) { return new Redis(redis, metrics); }

        @Bean
        @ConditionalOnProperty(name = "vas.rate-limit", havingValue = "memory", matchIfMissing = true)
        RateLimiter memoryLimiter() { return new InMemory(); }
    }
}

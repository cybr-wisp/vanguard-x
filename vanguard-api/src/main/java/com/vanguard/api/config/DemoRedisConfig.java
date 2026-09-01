package com.vanguard.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * In demo mode, provide a Redis template that connects to localhost:6379.
 * If Redis is not running, repository calls will fail silently (the demo
 * simulator catches and ignores Redis errors).
 */
@Configuration
@ConditionalOnProperty(name = "vanguard.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoRedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("localhost", 6379);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        // Don't fail on startup if Redis is down
        factory.setValidateConnection(false);
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}

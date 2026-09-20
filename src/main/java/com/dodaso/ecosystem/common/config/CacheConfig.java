package com.dodaso.ecosystem.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration("commonCacheConfig")
public class CacheConfig {

  @Bean(name = "commonCacheManager")
  @Primary
  public CaffeineCacheManager commonCacheManager() {
    Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
        .initialCapacity(100)
        .maximumSize(500)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .recordStats();
    CaffeineCacheManager caffeineCacheManager = new CaffeineCacheManager("common");
    caffeineCacheManager.setCaffeine(caffeine);
    return caffeineCacheManager;
  }
}
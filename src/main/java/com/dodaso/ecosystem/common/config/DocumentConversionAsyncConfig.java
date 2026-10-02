package com.dodaso.ecosystem.common.config;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated thread pool for office-document-to-PDF conversion via
 * Gotenberg, kept separate from ThumbnailAsyncConfig's pool -- a
 * conversion call involves a network round trip to the Gotenberg
 * container (and LibreOffice doing real work on the other end), which can
 * run noticeably longer than an in-process thumbnail extraction; sharing
 * one pool would let a burst of office-document uploads starve ordinary
 * image/PDF thumbnail generation, and vice versa.
 *
 * @EnableAsync is NOT repeated here -- ThumbnailAsyncConfig already
 * declares it once for the whole application context.
 *
 * Same CallerRunsPolicy rationale as ThumbnailAsyncConfig: a slow
 * conversion is recoverable, a silently dropped one is not.
 */
@Configuration
public class DocumentConversionAsyncConfig {

    public static final String DOCUMENT_CONVERSION_EXECUTOR_BEAN = "documentConversionTaskExecutor";

    @Bean(DOCUMENT_CONVERSION_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor documentConversionTaskExecutor() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("doc-conversion-");
        final RejectedExecutionHandler callerRuns = new ThreadPoolExecutor.CallerRunsPolicy();
        executor.setRejectedExecutionHandler(callerRuns);
        executor.initialize();
        return executor;
    }
}

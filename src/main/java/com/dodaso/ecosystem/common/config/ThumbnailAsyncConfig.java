package com.dodaso.ecosystem.common.config;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated thread pool for thumbnail generation, kept separate from
 * Spring's default @Async executor (and from request-handling threads) so
 * a burst of image uploads can't starve unrelated @Async work elsewhere
 * in the service, and so thumbnail work never blocks the HTTP response to
 * the uploading caller -- the caller gets its FileUploadDTO back as soon
 * as the blob + file_upload row are committed, without waiting on
 * ImageIO decode/scale/encode + a second blob upload.
 *
 * CallerRunsPolicy is deliberate for the rejection case: if the queue
 * fills up, generation runs on the calling (upload request) thread rather
 * than being dropped -- a slow thumbnail is recoverable, a silently lost
 * one is not.
 */
@Configuration
@EnableAsync
public class ThumbnailAsyncConfig {

    public static final String THUMBNAIL_EXECUTOR_BEAN = "thumbnailTaskExecutor";

    @Bean(THUMBNAIL_EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor thumbnailTaskExecutor() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("thumbnail-gen-");
        final RejectedExecutionHandler callerRuns = new ThreadPoolExecutor.CallerRunsPolicy();
        executor.setRejectedExecutionHandler(callerRuns);
        executor.initialize();
        return executor;
    }
}

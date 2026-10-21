package com.dodaso.ecosystem.common.service;

import com.dodaso.ecosystem.common.config.CommonPurgeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.TimeZone;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-10-21. Nightly trigger of {@link FilePurgeService#runRetention()}. The schedule is
 * {@code common.purge.cron} (default 03:00 UTC). With {@code common.purge.enabled=false}, the
 * default, nothing is scheduled.
 *
 * <p>Why the job schedules itself: this jar is also scanned into ecws-service, which has no
 * {@code @EnableScheduling}. Turning scheduling on globally from here would silently start
 * ecws-service's TaskDependencySyncScheduler. A private one-thread scheduler avoids that, and
 * stays idle in every service where {@code common.purge.enabled} is not set.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilePurgeJob {

  private final CommonPurgeProperties properties;
  private final FilePurgeService filePurgeService;

  private ThreadPoolTaskScheduler scheduler;

  @PostConstruct
  void start() {
    if (!properties.isEnabled()) {
      return;
    }
    scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("common-purge-");
    scheduler.initialize();
    scheduler.schedule(this::run, new CronTrigger(properties.getCron(), TimeZone.getTimeZone("UTC")));
    log.info("PURGE file job scheduled at '{}' UTC, dry run: {}", properties.getCron(),
        properties.isDryRun());
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) {
      scheduler.shutdown();
    }
  }

  /** One pass. Never throws, so the schedule keeps going. */
  public void run() {
    try {
      filePurgeService.runRetention();
    } catch (final RuntimeException e) {
      log.error("PURGE run failed: {}", e.getMessage(), e);
    }
  }
}

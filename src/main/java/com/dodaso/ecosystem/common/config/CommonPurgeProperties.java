package com.dodaso.ecosystem.common.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ADDED 2026-10-21. Settings of the purge job for soft-deleted files, bound from
 * {@code common.purge.*}.
 *
 * <p>The job is off ({@code enabled=false}) and, once on, only reports ({@code dry-run=true})
 * until it is switched to really delete. A purge cannot be undone.
 *
 * <p>{@code retention-days} is the default number of days a soft-deleted file is kept.
 * {@code retention-days-by-source} overrides it per source app (for example ELCM or ECWS), so the
 * policy can differ by product or customer. A value of 0 or less keeps that source forever.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "common.purge")
public class CommonPurgeProperties {

  /** Master switch of the nightly job. */
  private boolean enabled = false;

  /** When true the job only logs what it would remove. */
  private boolean dryRun = true;

  /** Default retention in days. */
  private int retentionDays = 90;

  /** Retention in days by source app, for example {@code retention-days-by-source[ECWS]=365}. */
  private Map<String, Integer> retentionDaysBySource = new HashMap<>();

  /** Most files handled per source app in one run. */
  private int batchSize = 200;

  /** Cron of the nightly run, in UTC. */
  private String cron = "0 0 3 * * *";

  /** Retention in days for a source app. Source app names are compared without case. */
  public int retentionFor(final String sourceApp) {
    if (sourceApp != null) {
      for (Map.Entry<String, Integer> entry : retentionDaysBySource.entrySet()) {
        if (entry.getKey() != null
            && entry.getKey().trim().toLowerCase(Locale.ROOT)
                .equals(sourceApp.trim().toLowerCase(Locale.ROOT))
            && entry.getValue() != null) {
          return entry.getValue();
        }
      }
    }
    return retentionDays;
  }
}

package com.dodaso.ecosystem.common.service;

import com.dodaso.ecosystem.common.config.CommonPurgeProperties;
import com.dodaso.ecosystem.common.entity.FileUpload;
import com.dodaso.ecosystem.common.repository.FileUploadRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * ADDED 2026-10-21. The purge of files in common-service. Two entry points:
 *
 * <ul>
 *   <li>{@link #purgeByIds}: the product services ask for files to be removed.
 *   <li>{@link #runRetention}: the nightly job removes soft-deleted files that have passed their
 *       retention period, whoever deleted them. This catches the ECWS files that ecws-service
 *       soft-deleted and any other orphan.
 * </ul>
 *
 * <p>Each file is removed on its own, so one failure never stops the rest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilePurgeService {

  private final FilePurger filePurger;
  private final FileUploadRepository fileUploadRepository;
  private final CommonPurgeProperties properties;

  /**
   * Removes the listed files.
   *
   * @return the ids that are gone (removed now, or not there at all); an id that failed is not in
   *     the result, so the caller retries it
   */
  public Set<Long> purgeByIds(final Collection<Long> ids, final String requiredSourceApp) {
    final Set<Long> gone = new LinkedHashSet<>();
    if (ids == null) {
      return gone;
    }
    for (final Long id : ids) {
      if (id == null) {
        continue;
      }
      try {
        final FilePurger.Outcome outcome = filePurger.purge(id, requiredSourceApp);
        if (outcome != FilePurger.Outcome.SKIPPED_OTHER_SOURCE) {
          gone.add(id);
        }
      } catch (final RuntimeException e) {
        log.error("PURGE file_upload {} failed, will be retried: {}", id, e.getMessage());
      }
    }
    return gone;
  }

  /** The nightly retention run. Honours the dry-run switch. */
  public void runRetention() {
    final boolean dryRun = properties.isDryRun();
    int purged = 0;
    int failed = 0;
    int wouldPurge = 0;
    for (final String sourceApp : fileUploadRepository.findSourceAppsWithSoftDeleted()) {
      final int days = properties.retentionFor(sourceApp);
      if (days <= 0) {
        log.info("PURGE source app {} is kept forever (retention {} days)", sourceApp, days);
        continue;
      }
      final LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
      final List<FileUpload> due =
          fileUploadRepository.findByActiveIndFalseAndSourceAppAndUpdatedAtBeforeOrderByIdAsc(
              sourceApp, cutoff, PageRequest.of(0, Math.max(1, properties.getBatchSize())));
      for (final FileUpload file : due) {
        if (dryRun) {
          wouldPurge++;
          log.info("PURGE dry run: would remove file_upload {} ({}) of {}, deleted {}",
              file.getId(), file.getFileName(), sourceApp, file.getUpdatedAt());
          continue;
        }
        try {
          filePurger.purge(file.getId(), null);
          purged++;
        } catch (final RuntimeException e) {
          failed++;
          log.error("PURGE file_upload {} failed, will be retried: {}", file.getId(),
              e.getMessage());
        }
      }
    }
    if (dryRun) {
      log.info("PURGE dry run finished: {} file(s) would be removed", wouldPurge);
    } else {
      log.info("PURGE finished: {} file(s) removed, {} failed", purged, failed);
    }
  }
}

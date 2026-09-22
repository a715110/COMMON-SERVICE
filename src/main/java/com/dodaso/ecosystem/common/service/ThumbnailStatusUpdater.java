package com.dodaso.ecosystem.common.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dodaso.ecosystem.common.entity.FileThumbnail;
import com.dodaso.ecosystem.common.entity.LkpThumbnailStatus;
import com.dodaso.ecosystem.common.repository.FileThumbnailRepository;
import com.dodaso.ecosystem.common.repository.LkpThumbnailStatusRepository;
import com.dodaso.ecosystem.common.dto.BlobUploadResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Separate bean (not just private methods on ThumbnailGenerationService)
 * purely so @Transactional actually applies: Spring's transaction advice
 * is proxy-based, and a method calling another @Transactional method on
 * "this" bypasses the proxy entirely (self-invocation), silently running
 * with no transaction. Each status transition here is its own short,
 * separately-committed transaction -- deliberately not one long
 * transaction spanning the whole async job, since the whole point is for
 * "started"/"completed"/"failed" to each become visible to any other
 * reader (e.g. the thumbnail-retrieval endpoint) as soon as they happen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThumbnailStatusUpdater {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final FileThumbnailRepository fileThumbnailRepository;
    private final LkpThumbnailStatusRepository lkpThumbnailStatusRepository;

    @Transactional
    public void markStarted(final Long thumbnailId) {
        final FileThumbnail thumbnail = fileThumbnailRepository.findById(thumbnailId).orElse(null);
        if (thumbnail == null) {
            log.warn("file_thumbnail id={} disappeared before processing could start", thumbnailId);
            return;
        }
        thumbnail.setStatus(requireStatus(ThumbnailGenerationService.THUMBNAIL_PROCESSING_CODE));
        thumbnail.setStartedAt(LocalDateTime.now());
        thumbnail.setAttemptCount(thumbnail.getAttemptCount() == null ? 1 : thumbnail.getAttemptCount() + 1);
        fileThumbnailRepository.save(thumbnail);
    }

    @Transactional
    public void markCompleted(final Long thumbnailId, final BlobUploadResult thumbnailBlob,
            final int widthPx, final int heightPx) {
        final FileThumbnail thumbnail = fileThumbnailRepository.findById(thumbnailId).orElse(null);
        if (thumbnail == null) {
            log.warn("file_thumbnail id={} disappeared before it could be marked COMPLETED", thumbnailId);
            return;
        }
        thumbnail.setStatus(requireStatus(ThumbnailGenerationService.THUMBNAIL_COMPLETED_CODE));
        thumbnail.setBlobContainer(thumbnailBlob.blobContainer());
        thumbnail.setBlobPath(thumbnailBlob.blobPath());
        thumbnail.setBlobUrl(thumbnailBlob.blobUrl());
        thumbnail.setWidthPx(widthPx);
        thumbnail.setHeightPx(heightPx);
        thumbnail.setCompletedAt(LocalDateTime.now());
        thumbnail.setLastErrorMessage(null);
        fileThumbnailRepository.save(thumbnail);
    }

    @Transactional
    public void markFailed(final Long thumbnailId, final Exception ex) {
        final FileThumbnail thumbnail = fileThumbnailRepository.findById(thumbnailId).orElse(null);
        if (thumbnail == null) {
            log.warn("file_thumbnail id={} disappeared before it could be marked FAILED", thumbnailId);
            return;
        }
        thumbnail.setStatus(requireStatus(ThumbnailGenerationService.THUMBNAIL_FAILED_CODE));
        final String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        thumbnail.setLastErrorMessage(
                message.length() > MAX_ERROR_MESSAGE_LENGTH ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) : message);
        thumbnail.setCompletedAt(LocalDateTime.now());
        fileThumbnailRepository.save(thumbnail);
    }

    private LkpThumbnailStatus requireStatus(final String code) {
        final Optional<LkpThumbnailStatus> status = lkpThumbnailStatusRepository.findByCode(code);
        return status.orElseThrow(() -> new IllegalStateException(
                "lkp_thumbnail_status has no seeded row for code='" + code + "' -- seed PENDING/PROCESSING/"
                        + "COMPLETED/FAILED before thumbnail generation can run"));
    }
}

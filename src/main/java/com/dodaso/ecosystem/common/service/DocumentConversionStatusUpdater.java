package com.dodaso.ecosystem.common.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dodaso.ecosystem.common.entity.DocumentConversion;
import com.dodaso.ecosystem.common.entity.LkpDocumentConversionStatus;
import com.dodaso.ecosystem.common.repository.DocumentConversionRepository;
import com.dodaso.ecosystem.common.repository.LkpDocumentConversionStatusRepository;
import com.dodaso.ecosystem.common.service.dto.BlobUploadResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Separate bean from DocumentConversionService for the same reason
 * ThumbnailStatusUpdater is separate from ThumbnailGenerationService:
 * Spring's @Transactional is proxy-based, and a method invoking another
 * @Transactional method on "this" bypasses the proxy (self-invocation),
 * silently running without a transaction. Each transition here commits on
 * its own as soon as it happens, so a status poll from the UI sees
 * started/completed/failed independently of how long the rest of the
 * async job takes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentConversionStatusUpdater {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final DocumentConversionRepository documentConversionRepository;
    private final LkpDocumentConversionStatusRepository lkpDocumentConversionStatusRepository;

    @Transactional
    public void markStarted(final Long conversionId) {
        final DocumentConversion conversion = documentConversionRepository.findById(conversionId).orElse(null);
        if (conversion == null) {
            log.warn("document_conversion id={} disappeared before processing could start", conversionId);
            return;
        }
        conversion.setStatus(requireStatus(DocumentConversionService.CONVERSION_PROCESSING_CODE));
        conversion.setStartedAt(LocalDateTime.now());
        conversion.setAttemptCount(conversion.getAttemptCount() == null ? 1 : conversion.getAttemptCount() + 1);
        documentConversionRepository.save(conversion);
    }

    @Transactional
    public void markCompleted(final Long conversionId, final BlobUploadResult pdfBlob) {
        final DocumentConversion conversion = documentConversionRepository.findById(conversionId).orElse(null);
        if (conversion == null) {
            log.warn("document_conversion id={} disappeared before it could be marked COMPLETED", conversionId);
            return;
        }
        conversion.setStatus(requireStatus(DocumentConversionService.CONVERSION_COMPLETED_CODE));
        conversion.setBlobContainer(pdfBlob.blobContainer());
        conversion.setBlobPath(pdfBlob.blobPath());
        conversion.setBlobUrl(pdfBlob.blobUrl());
        conversion.setCompletedAt(LocalDateTime.now());
        conversion.setLastErrorMessage(null);
        documentConversionRepository.save(conversion);
    }

    @Transactional
    public void markFailed(final Long conversionId, final Exception ex) {
        final DocumentConversion conversion = documentConversionRepository.findById(conversionId).orElse(null);
        if (conversion == null) {
            log.warn("document_conversion id={} disappeared before it could be marked FAILED", conversionId);
            return;
        }
        conversion.setStatus(requireStatus(DocumentConversionService.CONVERSION_FAILED_CODE));
        final String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        conversion.setLastErrorMessage(
                message.length() > MAX_ERROR_MESSAGE_LENGTH ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) : message);
        conversion.setCompletedAt(LocalDateTime.now());
        documentConversionRepository.save(conversion);
    }

    private LkpDocumentConversionStatus requireStatus(final String code) {
        final Optional<LkpDocumentConversionStatus> status = lkpDocumentConversionStatusRepository.findByCode(code);
        return status.orElseThrow(() -> new IllegalStateException(
                "lkp_document_conversion_status has no seeded row for code='" + code + "' -- seed PENDING/"
                        + "PROCESSING/COMPLETED/FAILED before document conversion can run"));
    }
}

package com.dodaso.ecosystem.common.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.dodaso.ecosystem.common.service.dto.BlobUploadContext;
import com.dodaso.ecosystem.common.service.dto.BlobUploadResult;
import com.dodaso.ecosystem.common.service.dto.FileUploadRequest;
import com.dodaso.ecosystem.common.service.handler.FileUploadDownloadHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Processes every document_conversion row FileUploadService queues as
 * PENDING -- one per upload that DocumentConversionEligibility judged
 * eligible (office formats a browser can't render natively: .docx/.xlsx/
 * .pptx and their legacy .doc/.xls/.ppt equivalents). Unlike thumbnail
 * generation, this is NOT queued for every upload -- a PDF or image
 * already previews fine on its own and never gets a document_conversion
 * row at all.
 *
 * "Processing" means: send the original file's bytes to the self-hosted
 * Gotenberg container (GotenbergClient, wrapping LibreOffice) and get back
 * a converted PDF's bytes, then upload that PDF through the same
 * FileUploadDownloadHandler/BlobUploadContext as the original file, into
 * the same source container -- so it inherits the same tenant path/tag
 * scoping. Its blob file name is prefixed "converted_" and its content
 * type is always application/pdf.
 *
 * Client-visible flow: elcm-ui's DocumentViewerBean polls
 * GET /{id}/conversion/status after opening the preview pane for an
 * office-format file; once it reports COMPLETED, the UI fetches
 * GET /{id}/conversion and renders those PDF bytes in the same
 * <iframe>/PDF.js viewer already used for native PDF uploads -- no
 * separate rendering path needed on the browser side.
 *
 * Status transitions (PENDING->PROCESSING->COMPLETED/FAILED) are
 * delegated to DocumentConversionStatusUpdater, a separate bean, for the
 * same self-invocation/proxy reason documented on ThumbnailGenerationService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentConversionService {

    public static final String CONVERSION_PROCESSING_CODE = "PROCESSING";
    public static final String CONVERSION_COMPLETED_CODE = "COMPLETED";
    public static final String CONVERSION_FAILED_CODE = "FAILED";

    private final DocumentConversionStatusUpdater documentConversionStatusUpdater;
    private final FileUploadDownloadHandler fileUploadDownloadHandler;
    private final GotenbergClient gotenbergClient;

    /** Total tries per conversion (first attempt included) for transient Gotenberg errors. */
    @Value("${gotenberg.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${gotenberg.retry.initial-backoff-ms:2000}")
    private long initialBackoffMs;

    @Value("${gotenberg.retry.backoff-multiplier:2.0}")
    private double backoffMultiplier;

    @Async(com.dodaso.ecosystem.common.config.DocumentConversionAsyncConfig.DOCUMENT_CONVERSION_EXECUTOR_BEAN)
    public void convertToPdfAsync(final Long conversionId, final Long fileUploadId, final byte[] originalContent,
                                   final String fileName, final String sourceContainer,
                                   final BlobUploadContext context) {
        try {
            // Each attempt marks the row PROCESSING and bumps attempt_count. Only
            // transient errors (container down or restarting, 5xx, timeout) are
            // retried; a file Gotenberg rejects outright fails at once.
            final byte[] pdfBytes = GotenbergRetry.execute(
                () -> gotenbergClient.convertToPdf(fileName, originalContent),
                () -> documentConversionStatusUpdater.markStarted(conversionId),
                maxAttempts, initialBackoffMs, backoffMultiplier,
                GotenbergClient::isTransient, Thread::sleep);

            final FileUploadRequest pdfRequest = new FileUploadRequest(
                "converted_" + conversionId + ".pdf", "application/pdf", pdfBytes);
            final List<BlobUploadResult> uploadResults = fileUploadDownloadHandler.uploadFiles(
                List.of(pdfRequest), sourceContainer, context);
            final BlobUploadResult pdfBlob = uploadResults.get(0);

            documentConversionStatusUpdater.markCompleted(conversionId, pdfBlob);
            log.info("Document conversion ready for file_upload id={} (conversion id={})", fileUploadId, conversionId);
        } catch (final Exception ex) {
            log.error("Document conversion failed for file_upload id={} (conversion id={}): {}",
                fileUploadId, conversionId, ex.getMessage(), ex);
            documentConversionStatusUpdater.markFailed(conversionId, ex);
        }
    }
}

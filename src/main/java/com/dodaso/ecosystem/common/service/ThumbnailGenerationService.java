package com.dodaso.ecosystem.common.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.dodaso.ecosystem.common.dto.BlobUploadContext;
import com.dodaso.ecosystem.common.dto.BlobUploadResult;
import com.dodaso.ecosystem.common.dto.FileUploadRequest;
import com.dodaso.ecosystem.common.service.handler.FileUploadDownloadHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Processes every file_thumbnail row FileUploadService queues as PENDING
 * -- one per upload, regardless of file type. What "processing" means
 * depends on ThumbnailFormatClassifier's verdict for the upload's
 * content_type/fileName:
 *   - PDF, DOCX, or image (JPG/JPEG/PNG/TIFF) -- the "primary" formats --
 *     get a REAL preview extracted from the file's own bytes, via
 *     Thumbnails4jExtractor (thumbnails4j, an Elastic open-source
 *     library: https://github.com/elastic/thumbnails4j). This also
 *     covers TIFF, which the JDK's own ImageIO has no reader for --
 *     thumbnails4j's image module handles it directly, no format
 *     exclusion needed the way an ImageIO-only implementation required.
 *   - Every other format gets a generated, representative default icon
 *     (DefaultThumbnailIconResolver) -- e.g. an XLSX upload gets a
 *     green "XLSX" icon, an unrecognized format gets a generic one. This
 *     still goes through the full PENDING -> PROCESSING -> COMPLETED
 *     pipeline below (rather than being special-cased at the point
 *     FileUploadService queues it) so every file_thumbnail row behaves
 *     identically to callers: status polling, attemptCount, and the
 *     retrieval endpoint don't need to know or care which path produced
 *     the image.
 *
 * Client-visible flow this supports: the upload response returns
 * immediately with the file_upload row in a "staged" state and no
 * thumbnail yet; the UI is expected to render a generic per-type
 * placeholder client-side in the meantime (using file_upload.content_type
 * alone -- it doesn't need to wait on this service for that first paint)
 * and swap in the real thumbnail once a later poll of
 * GET /{id}/thumbnail/status reports COMPLETED, typically on next refresh.
 *
 * Other design choices carried over unchanged:
 *   - Triggered directly by FileUploadService (afterCommit + @Async)
 *     rather than a separate poller -- the row's existence and its one
 *     caller are both known at insert time. attemptCount/lastErrorMessage
 *     still make failed rows visible for a manual/administrative retry
 *     later if one is ever added.
 *   - The thumbnail image itself is uploaded back through the same
 *     FileUploadDownloadHandler/BlobUploadContext as the original file,
 *     into the same container, so it inherits the same tenant path/tag
 *     scoping; its file name is prefixed "thumb_" and its content type
 *     is always image/png, whether it came from thumbnails4j or from the
 *     default-icon generator.
 *   - Status transitions (PENDING->PROCESSING->COMPLETED/FAILED) are
 *     delegated to ThumbnailStatusUpdater, a separate bean, rather than
 *     done as @Transactional methods on this class -- a method calling
 *     another @Transactional method on "this" bypasses Spring's
 *     proxy-based transaction advice (self-invocation), so it has to be
 *     a different bean for @Transactional to actually take effect.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThumbnailGenerationService {

    public static final String THUMBNAIL_PROCESSING_CODE = "PROCESSING";
    public static final String THUMBNAIL_COMPLETED_CODE = "COMPLETED";
    public static final String THUMBNAIL_FAILED_CODE = "FAILED";

    /** Bounding box a thumbnail is scaled to fit within, preserving
     * aspect ratio -- chosen as a reasonable general-purpose preview
     * size; not currently configurable since no caller has asked for a
     * different size yet. Also used as the fixed size of generated
     * default icons, so every file_thumbnail row's image is comparably
     * sized regardless of which path produced it. */
    private static final int MAX_DIMENSION_PX = 320;

    private final ThumbnailStatusUpdater thumbnailStatusUpdater;
    private final FileUploadDownloadHandler fileUploadDownloadHandler;
    private final Thumbnails4jExtractor thumbnails4jExtractor;
    private final DefaultThumbnailIconResolver defaultThumbnailIconResolver;

    @Async(com.dodaso.ecosystem.common.config.ThumbnailAsyncConfig.THUMBNAIL_EXECUTOR_BEAN)
    public void generateThumbnailAsync(final Long thumbnailId, final Long fileUploadId, final byte[] originalContent,
                                        final String contentType, final String fileName, final String sourceContainer,
                                        final BlobUploadContext context) {
        try {
            thumbnailStatusUpdater.markStarted(thumbnailId);

            final ThumbnailFormatClassifier.Format format = ThumbnailFormatClassifier.classify(contentType, fileName);
            final byte[] pngBytes;
            final int widthPx;
            final int heightPx;

            if (format == ThumbnailFormatClassifier.Format.UNSUPPORTED) {
                // No content-aware preview possible -- a representative icon
                // by extension/content-type IS the thumbnail for this row,
                // not a fallback after a failed attempt.
                pngBytes = defaultThumbnailIconResolver.resolveIcon(contentType, fileName);
                final BufferedImage icon = ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
                widthPx = icon.getWidth();
                heightPx = icon.getHeight();
            } else {
                final BufferedImage extracted = switch (format) {
                    case PDF -> thumbnails4jExtractor.extractPdfThumbnail(originalContent, MAX_DIMENSION_PX);
                    case DOCX -> thumbnails4jExtractor.extractDocxThumbnail(originalContent, MAX_DIMENSION_PX);
                    case IMAGE -> thumbnails4jExtractor.extractImageThumbnail(originalContent, MAX_DIMENSION_PX);
                    case UNSUPPORTED -> throw new IllegalStateException("unreachable -- handled above");
                };
                pngBytes = encodeAsPng(extracted);
                widthPx = extracted.getWidth();
                heightPx = extracted.getHeight();
            }

            final FileUploadRequest thumbnailRequest = new FileUploadRequest(
                "thumb_" + thumbnailId + ".png", "image/png", pngBytes);
            final List<BlobUploadResult> uploadResults = fileUploadDownloadHandler.uploadFiles(
                List.of(thumbnailRequest), sourceContainer, context);
            final BlobUploadResult thumbnailBlob = uploadResults.get(0);

            thumbnailStatusUpdater.markCompleted(thumbnailId, thumbnailBlob, widthPx, heightPx);
            log.info("Thumbnail ready for file_upload id={} (thumbnail id={}, format={}, {}x{})",
                fileUploadId, thumbnailId, format, widthPx, heightPx);
        } catch (final Exception ex) {
            log.error("Thumbnail generation failed for file_upload id={} (thumbnail id={}): {}",
                fileUploadId, thumbnailId, ex.getMessage(), ex);
            thumbnailStatusUpdater.markFailed(thumbnailId, ex);
        }
    }

    private byte[] encodeAsPng(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}

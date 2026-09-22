package com.dodaso.ecosystem.common.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
 * Processes the file_thumbnail rows that FileUploadService queues as
 * PENDING. This is the worker the original schema (requested_at /
 * started_at / completed_at / attempt_count / last_error_message) was
 * designed for but that had never been implemented -- previously the
 * PENDING row was created and nothing ever picked it up.
 *
 * Design choices:
 *   - Decode/scale/re-encode uses only java.desktop's ImageIO -- no new
 *     third-party dependency (Thumbnailator/imgscalar/etc.) -- since
 *     ImageIO already covers the formats file_upload accepts as
 *     "thumbnailable" (JPEG/PNG; GIF and BMP also decode fine via
 *     ImageIO, though animated GIFs thumbnail only their first frame).
 *     TIFF is accepted as an upload content type by the UI but the JDK's
 *     built-in ImageIO has no TIFF reader, so TIFF uploads are excluded
 *     from thumbnailing below rather than failing loudly per attempt.
 *   - Triggered directly by FileUploadService (afterCommit + @Async)
 *     rather than a separate poller, since the row's existence and its
 *     one caller are both known at insert time -- a poll loop scanning
 *     for status=PENDING would only add latency and a second moving
 *     part with no benefit yet. attemptCount/lastErrorMessage still make
 *     failed rows visible for a manual/administrative retry later if
 *     one is ever added.
 *   - The thumbnail image itself is uploaded back through the same
 *     FileUploadDownloadHandler/BlobUploadContext as the original file,
 *     into the same container, so it inherits the same tenant path/tag
 *     scoping; its file name is prefixed "thumb_" and its content type
 *     is always image/png regardless of the source format, since the
 *     re-encode always targets PNG (lossless, universally supported for
 *     the small dimensions involved).
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
     * different size yet. */
    private static final int MAX_DIMENSION_PX = 320;

    private final ThumbnailStatusUpdater thumbnailStatusUpdater;
    private final FileUploadDownloadHandler fileUploadDownloadHandler;

    @Async(com.dodaso.ecosystem.common.config.ThumbnailAsyncConfig.THUMBNAIL_EXECUTOR_BEAN)
    public void generateThumbnailAsync(final Long thumbnailId, final Long fileUploadId, final byte[] originalContent,
                                        final String contentType, final String sourceContainer,
                                        final BlobUploadContext context) {
        try {
            thumbnailStatusUpdater.markStarted(thumbnailId);

            if ("image/tiff".equalsIgnoreCase(contentType)) {
                // No built-in ImageIO reader for TIFF -- fail fast with a
                // clear reason rather than letting ImageIO.read() return
                // null and reporting a generic decode failure below.
                throw new IOException("TIFF is not supported by the built-in image decoder; "
                    + "no thumbnail can be generated for content type " + contentType);
            }

            final BufferedImage original = ImageIO.read(new ByteArrayInputStream(originalContent));
            if (original == null) {
                throw new IOException("ImageIO could not decode file_upload id=" + fileUploadId
                    + " (content type " + contentType + ") -- unsupported or corrupt image data");
            }

            final BufferedImage scaled = scale(original, MAX_DIMENSION_PX);
            final byte[] pngBytes = encodeAsPng(scaled);

            final FileUploadRequest thumbnailRequest = new FileUploadRequest(
                "thumb_" + thumbnailId + ".png", "image/png", pngBytes);
            final List<BlobUploadResult> uploadResults = fileUploadDownloadHandler.uploadFiles(
                List.of(thumbnailRequest), sourceContainer, context);
            final BlobUploadResult thumbnailBlob = uploadResults.get(0);

            thumbnailStatusUpdater.markCompleted(thumbnailId, thumbnailBlob, scaled.getWidth(), scaled.getHeight());
            log.info("Thumbnail generated for file_upload id={} (thumbnail id={}, {}x{})",
                fileUploadId, thumbnailId, scaled.getWidth(), scaled.getHeight());
        } catch (final Exception ex) {
            log.error("Thumbnail generation failed for file_upload id={} (thumbnail id={}): {}",
                fileUploadId, thumbnailId, ex.getMessage(), ex);
            thumbnailStatusUpdater.markFailed(thumbnailId, ex);
        }
    }

    /** Scales preserving aspect ratio so the longer side equals
     * maxDimension; never upscales an image already smaller than that. */
    private BufferedImage scale(final BufferedImage original, final int maxDimension) {
        final int originalWidth = original.getWidth();
        final int originalHeight = original.getHeight();
        final double scaleFactor = Math.min(
            1.0, maxDimension / (double) Math.max(originalWidth, originalHeight));
        final int targetWidth = Math.max(1, (int) Math.round(originalWidth * scaleFactor));
        final int targetHeight = Math.max(1, (int) Math.round(originalHeight * scaleFactor));

        final BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private byte[] encodeAsPng(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}

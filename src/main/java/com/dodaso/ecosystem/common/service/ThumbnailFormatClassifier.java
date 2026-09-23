package com.dodaso.ecosystem.common.service;

import java.util.Locale;
import java.util.Set;

/**
 * Decides how a given upload's thumbnail gets built. Two paths exist:
 *   - PDF / DOCX / image (JPG, JPEG, PNG, TIFF) -- the "primary" formats --
 *     get a REAL preview generated from the file's own content, via
 *     Thumbnails4jExtractor.
 *   - Everything else gets a generated, representative default icon
 *     (DefaultThumbnailIconResolver) -- no attempt is made to peek inside
 *     an unsupported format.
 *
 * Classification checks content_type first (case-insensitively), then
 * falls back to the file's extension when content_type is missing, generic
 * (e.g. application/octet-stream from a client that didn't bother setting
 * it), or otherwise doesn't match a known value -- browsers and some
 * upload clients are inconsistent about what they send.
 */
final class ThumbnailFormatClassifier {

    enum Format {
        PDF,
        DOCX,
        IMAGE,
        UNSUPPORTED
    }

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
        "image/jpeg", "image/jpg", "image/png", "image/tiff");

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "tif", "tiff");

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String DOCX_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private ThumbnailFormatClassifier() {
    }

    static Format classify(final String contentType, final String fileName) {
        final String normalizedType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);

        if (PDF_CONTENT_TYPE.equals(normalizedType)) {
            return Format.PDF;
        }
        if (DOCX_CONTENT_TYPE.equals(normalizedType)) {
            return Format.DOCX;
        }
        if (IMAGE_CONTENT_TYPES.contains(normalizedType)) {
            return Format.IMAGE;
        }

        // content_type was missing/generic -- fall back to the extension.
        final String extension = extensionOf(fileName);
        if ("pdf".equals(extension)) {
            return Format.PDF;
        }
        if ("docx".equals(extension)) {
            return Format.DOCX;
        }
        if (IMAGE_EXTENSIONS.contains(extension)) {
            return Format.IMAGE;
        }

        return Format.UNSUPPORTED;
    }

    private static String extensionOf(final String fileName) {
        if (fileName == null) {
            return "";
        }
        final int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

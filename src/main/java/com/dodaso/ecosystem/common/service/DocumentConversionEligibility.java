package com.dodaso.ecosystem.common.service;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether an upload is a candidate for Gotenberg-based conversion
 * to a previewable PDF. Mirrors ThumbnailFormatClassifier's content-type-
 * first/extension-fallback approach, but answers a simpler yes/no question
 * (there's no "which path" branching here the way thumbnails has
 * PDF/DOCX/IMAGE/UNSUPPORTED -- Gotenberg's /forms/libreoffice/convert
 * route accepts any of these office formats uniformly).
 *
 * Eligible formats: the "modern" OOXML formats (.docx/.xlsx/.pptx) and
 * their legacy binary equivalents (.doc/.xls/.ppt) -- anything LibreOffice
 * (and therefore Gotenberg) converts to PDF via the same endpoint. PDF and
 * image uploads are deliberately NOT eligible here even though Gotenberg
 * could technically round-trip a PDF -- they already preview natively in
 * a browser, so queuing a conversion row for them would be pure waste.
 */
final class DocumentConversionEligibility {

    private static final Set<String> OFFICE_CONTENT_TYPES = Set.of(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // docx
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",       // xlsx
        "application/vnd.openxmlformats-officedocument.presentationml.presentation", // pptx
        "application/msword",                                                      // doc
        "application/vnd.ms-excel",                                                 // xls
        "application/vnd.ms-powerpoint");                                           // ppt

    private static final Set<String> OFFICE_EXTENSIONS = Set.of(
        "docx", "xlsx", "pptx", "doc", "xls", "ppt");

    private DocumentConversionEligibility() {
    }

    static boolean isEligible(final String contentType, final String fileName) {
        final String normalizedType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (OFFICE_CONTENT_TYPES.contains(normalizedType)) {
            return true;
        }
        return OFFICE_EXTENSIONS.contains(extensionOf(fileName));
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

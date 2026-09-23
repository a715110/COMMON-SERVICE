package com.dodaso.ecosystem.common.service;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

/**
 * Generates a representative icon for any upload whose format
 * ThumbnailFormatClassifier didn't recognize as PDF/DOCX/image -- a plain
 * file-shape silhouette with the extension printed on it (e.g. "XLSX",
 * "PPTX", "ZIP"), colored by a small, extend-as-needed mapping so the
 * common Office formats are visually distinct from each other and from a
 * generic fallback.
 *
 * Deliberately generated in code rather than shipped as bundled PNG
 * assets: it needs no real branded artwork to be "representative" per the
 * requirement, keeps this feature dependency-free beyond thumbnails4j
 * itself, and is trivial to swap out later -- if the team wants real
 * branded icons instead, replace resolveIcon()'s body with a classpath
 * resource lookup (e.g. classpath:thumbnails/<extension>.png, falling
 * back to a generic icon resource) and delete the rendering code below;
 * nothing else in ThumbnailGenerationService needs to change either way,
 * since it only sees this class's public method.
 *
 * Rendered icons are cached per label (not per file) since they're
 * identical for every file sharing an extension -- generation is cheap
 * (~1-2ms) but there's no reason to repeat it.
 */
@Component
class DefaultThumbnailIconResolver {

    private static final int ICON_SIZE_PX = 240;

    private static final Map<String, Color> COLOR_BY_LABEL = Map.ofEntries(
        Map.entry("XLS", new Color(0x1D6F42)),
        Map.entry("XLSX", new Color(0x1D6F42)),
        Map.entry("PPT", new Color(0xB7472A)),
        Map.entry("PPTX", new Color(0xB7472A)),
        Map.entry("DOC", new Color(0x2B579A)),
        Map.entry("TXT", new Color(0x5B6B7D)),
        Map.entry("CSV", new Color(0x5B6B7D)),
        Map.entry("ZIP", new Color(0x7A5CC4)),
        Map.entry("RAR", new Color(0x7A5CC4)),
        Map.entry("MSG", new Color(0x3D5166)),
        Map.entry("EML", new Color(0x3D5166)));

    private static final Color DEFAULT_COLOR = new Color(0x5B6B7D);

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /** Representative icon bytes (PNG) for a file this service doesn't
     * know how to open -- keyed by extension when one is available,
     * falling back to a generic "FILE" icon otherwise. Never throws:
     * icon rendering has no external inputs that can fail short of an
     * IOException from ImageIO.write(), which would indicate a genuinely
     * broken JVM image stack, not a bad upload. */
    byte[] resolveIcon(final String contentType, final String fileName) {
        final String label = labelFor(fileName, contentType);
        return cache.computeIfAbsent(label, this::renderIcon);
    }

    private String labelFor(final String fileName, final String contentType) {
        final String extension = extensionOf(fileName);
        if (!extension.isEmpty()) {
            return extension.toUpperCase(Locale.ROOT);
        }
        if (contentType != null && !contentType.isBlank()) {
            final int slash = contentType.indexOf('/');
            final String subtype = slash >= 0 ? contentType.substring(slash + 1) : contentType;
            return subtype.toUpperCase(Locale.ROOT);
        }
        return "FILE";
    }

    private String extensionOf(final String fileName) {
        if (fileName == null) {
            return "";
        }
        final int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1);
    }

    private byte[] renderIcon(final String label) {
        final Color color = COLOR_BY_LABEL.getOrDefault(label, DEFAULT_COLOR);
        // A label longer than this doesn't fit the icon readably -- fall
        // back to a generic mark rather than truncating awkwardly.
        final String displayLabel = label.length() <= 5 ? label : "FILE";

        final BufferedImage image = new BufferedImage(ICON_SIZE_PX, ICON_SIZE_PX, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // Body: a page shape with a folded top-right corner, the
            // universal "document" silhouette.
            final int margin = 28;
            final int foldSize = 40;
            final int pageWidth = ICON_SIZE_PX - margin * 2;
            final int pageHeight = ICON_SIZE_PX - margin * 2;
            final int left = margin;
            final int top = margin;

            final java.awt.Polygon page = new java.awt.Polygon();
            page.addPoint(left, top);
            page.addPoint(left + pageWidth - foldSize, top);
            page.addPoint(left + pageWidth, top + foldSize);
            page.addPoint(left + pageWidth, top + pageHeight);
            page.addPoint(left, top + pageHeight);

            g.setColor(color);
            g.fill(page);

            // Folded corner, drawn a shade darker so it reads as folded.
            final java.awt.Polygon fold = new java.awt.Polygon();
            fold.addPoint(left + pageWidth - foldSize, top);
            fold.addPoint(left + pageWidth, top + foldSize);
            fold.addPoint(left + pageWidth - foldSize, top + foldSize);
            g.setColor(color.darker());
            g.fill(fold);

            // Label, centered in the lower portion of the page.
            g.setColor(Color.WHITE);
            final Font font = new Font("SansSerif", Font.BOLD, displayLabel.length() > 3 ? 30 : 36);
            g.setFont(font);
            final FontMetrics metrics = g.getFontMetrics();
            final int textWidth = metrics.stringWidth(displayLabel);
            final int textX = left + (pageWidth - textWidth) / 2;
            final int textY = top + pageHeight - 46;
            g.drawString(displayLabel, textX, textY);
        } finally {
            g.dispose();
        }

        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (final IOException ex) {
            // ImageIO.write() failing on an in-memory BufferedImage with the
            // "png" writer registered means the JVM's image I/O stack itself
            // is broken -- not something a retry or a different label fixes.
            throw new IllegalStateException("Failed to encode default thumbnail icon as PNG", ex);
        }
    }
}

package com.dodaso.ecosystem.common.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import co.elastic.thumbnails4j.core.Dimensions;
import co.elastic.thumbnails4j.core.Thumbnailer;
import co.elastic.thumbnails4j.core.ThumbnailingException;
import co.elastic.thumbnails4j.docx.DOCXThumbnailer;
import co.elastic.thumbnails4j.image.ImageThumbnailer;
import co.elastic.thumbnails4j.pdf.PDFThumbnailer;

/**
 * Thin adapter over thumbnails4j (https://github.com/elastic/thumbnails4j,
 * Apache 2.0) -- isolates every direct dependency on its API to this one
 * class, so if a class name below turns out wrong once the dependency is
 * actually resolved, only this file needs to change.
 *
 * Confirmed directly against thumbnails4j's own source (main branch,
 * checked 2026-09-22), one file per class -- not inferred:
 *   - PDFThumbnailer(): no-arg constructor (also shown in the README's
 *     usage example).
 *   - DOCXThumbnailer(): no-arg constructor (implicit default -- the
 *     class declares none of its own).
 *   - ImageThumbnailer(String thumbnailType): takes the OUTPUT format --
 *     "png" (keeps transparency, BufferedImage.TYPE_INT_ARGB) or anything
 *     else, including "jpg" (opaque, TYPE_INT_RGB). This is the one that
 *     originally shipped as a no-arg call and failed to compile -- fixed
 *     below to `new ImageThumbnailer("png")`, matching every other
 *     thumbnail this service produces (always PNG, for the transparency
 *     and universal-support reasons already documented on
 *     ThumbnailGenerationService).
 * All three implement the shared `Thumbnailer` interface's
 * `getThumbnails(InputStream, List<Dimensions>)`, returning
 * `List<BufferedImage>`, one entry per page/frame.
 */
@Component
class Thumbnails4jExtractor {

    /** Output format passed to ImageThumbnailer -- PNG for the same
     * reason every other thumbnail this service writes is PNG: lossless,
     * supports transparency, universally supported at these small sizes. */
    private static final String IMAGE_OUTPUT_FORMAT = "png";

    BufferedImage extractPdfThumbnail(final byte[] content, final int maxDimensionPx) throws IOException, ThumbnailingException{
        return extract(new PDFThumbnailer(), content, maxDimensionPx);
    }

    BufferedImage extractImageThumbnail(final byte[] content, final int maxDimensionPx) throws IOException, ThumbnailingException{
        return extract(new ImageThumbnailer(IMAGE_OUTPUT_FORMAT), content, maxDimensionPx);
    }

    BufferedImage extractDocxThumbnail(final byte[] content, final int maxDimensionPx) throws IOException, ThumbnailingException {
        return extract(new DOCXThumbnailer(), content, maxDimensionPx);
    }

    private BufferedImage extract(final Thumbnailer thumbnailer, final byte[] content, final int maxDimensionPx)
            throws IOException, ThumbnailingException {
        final List<Dimensions> targetDimensions = Collections.singletonList(
            new Dimensions(maxDimensionPx, maxDimensionPx));
        try (InputStream input = new ByteArrayInputStream(content)) {
            final List<BufferedImage> pages = thumbnailer.getThumbnails(input, targetDimensions);
            if (pages == null || pages.isEmpty()) {
                throw new IOException("thumbnails4j returned no pages/frames for this content");
            }
            return pages.get(0);
        }
    }
}
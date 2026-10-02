package com.dodaso.ecosystem.common.service;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import lombok.RequiredArgsConstructor;

/**
 * Thin wrapper around Gotenberg's LibreOffice route:
 * POST {gotenberg.base-url}/forms/libreoffice/convert
 * multipart/form-data, one part named "files" carrying the office
 * document (Gotenberg infers the conversion from the file's own
 * extension, so the original fileName must be preserved on the part) ->
 * response body is the converted PDF's raw bytes.
 *
 * Gotenberg's own job is synchronous from this client's point of view --
 * the async/queued behavior is layered on top by
 * DocumentConversionService, same shape as thumbnails4j being called
 * synchronously inside ThumbnailGenerationService's own @Async method.
 *
 * See https://gotenberg.dev/docs/routes#convert-with-libreoffice for the
 * endpoint's contract.
 */
@Component
@RequiredArgsConstructor
public class GotenbergClient {

    private final WebClient gotenbergWebClient;

    public byte[] convertToPdf(final String fileName, final byte[] content) {
        final MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("files", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        return gotenbergWebClient.post()
                .uri("/forms/libreoffice/convert")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(org.springframework.web.reactive.function.BodyInserters.fromMultipartData(bodyBuilder.build()))
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
    }
}

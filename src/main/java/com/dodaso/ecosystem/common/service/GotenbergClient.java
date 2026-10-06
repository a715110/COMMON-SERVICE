package com.dodaso.ecosystem.common.service;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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

    /** Upper bound for one conversion call; LibreOffice can hang on a bad file. */
    @Value("${gotenberg.timeout-seconds:120}")
    private long timeoutSeconds;

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
                .block(Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * True when retrying the same call could plausibly succeed: the container is
     * unreachable or restarting (connection errors), it answered 408/429/5xx, or
     * the call timed out. Any other 4xx means Gotenberg rejected this file, and it
     * would reject it again.
     */
    public static boolean isTransient(final Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
            if (current instanceof WebClientResponseException responseError) {
                final int status = responseError.getStatusCode().value();
                return status >= 500 || status == 408 || status == 429;
            }
            if (current instanceof WebClientRequestException
                    || current instanceof TimeoutException
                    || current instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }
}

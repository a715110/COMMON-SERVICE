package com.dodaso.ecosystem.common.service.dto;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Input to FileUploadDownloadHandler.uploadFiles() -- a plain byte[]-
 * carrying DTO rather than Spring's MultipartFile, so the handler layer
 * doesn't depend on the servlet/web layer. This is an internal transport
 * type local to common-service; the public contract callers (elcm-service)
 * see is FileUploadDTO / FileUploadDTOContainer from common-data-model,
 * built by FileUploadService after the Azure handler returns.
 */
@Getter
@AllArgsConstructor
public class FileUploadRequest implements Serializable {
    private final String fileName;
    private final String contentType;
    private final byte[] content;
}

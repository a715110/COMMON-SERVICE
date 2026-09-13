package com.dodaso.ecosystem.common.service.dto;

import java.io.Serializable;

/**
 * Internal-to-common-service result of pushing one file's bytes to Azure --
 * NOT the public contract. FileUploadService consumes this and maps it,
 * together with caller-supplied ownership info (sourceApp/ownerType/
 * ownerId), into a persisted FileUpload row / FileUploadDTO -- the actual
 * shape callers like elcm-service see.
 */
public record BlobUploadResult(
    String originalFileName,
    String blobName,       // path/key within the container, e.g. "2026/09/12/<uuid>__file.pdf"
    String containerName,
    String url,             // full https blob URL
    long sizeBytes,
    String contentType
) implements Serializable {
}

package com.dodaso.ecosystem.common.service.dto;

/**
 * What FileUploadDownloadHandler.uploadFiles() hands back per file once
 * the blob is written: enough to build (and later re-derive) the
 * FileUpload row without re-touching Azure. fileName is the ORIGINAL
 * file name as supplied by the caller, not the generated blobName.
 */
public record BlobUploadResult(String fileName, String blobPath, String blobContainer, String blobUrl,
                                long fileSize, String contentType) {
}

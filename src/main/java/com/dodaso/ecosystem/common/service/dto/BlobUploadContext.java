package com.dodaso.ecosystem.common.service.dto;

import java.io.Serializable;

/**
 * Batch-level info that applies uniformly to every file in one
 * FileUploadDownloadHandler.uploadFiles() call -- used to build the
 * {sourceApp}/{ownerType}/{ownerId}/{yyyy}/{MM}/{dd}/{uuid}__{fileName}
 * blob path (AzureBlobStorageHandler.buildBlobName) and to set each
 * blob's Azure Blob Index Tags (companyId/sourceApp/ownerType/ownerId),
 * per the container-naming/folder-structure decision made in chat: the
 * container/path stay app+owner-shaped, company scoping is done via
 * queryable tags rather than a path segment or a container-per-company
 * split, since company_id is a bigint with no natural human-readable
 * container-name form.
 */
public record BlobUploadContext(
    String sourceApp,
    String ownerType,
    Long ownerId,
    Long companyId
) implements Serializable {
}

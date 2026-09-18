package com.dodaso.ecosystem.common.service.dto;

import java.io.Serializable;

/**
 * Batch-level info that applies uniformly to every file in one
 * FileUploadDownloadHandler.uploadFiles() call -- used to build the
 * {companyId}/{sourceApp}/{ownerType}/{ownerId}/{yyyy}/{MM}/{dd}/{uuid}__
 * {fileName} blob path (AzureBlobStorageHandler.buildBlobName) and to set
 * each blob's Azure Blob Index Tags (companyId/sourceApp/ownerType/
 * ownerId). companyId leads the path (not just a tag) because it's the
 * tenant-isolation boundary -- see AzureBlobStorageHandler's class
 * Javadoc for the full reasoning.
 */
public record BlobUploadContext(
    String sourceApp,
    String ownerType,
    Long ownerId,
    Long companyId
) implements Serializable {
}

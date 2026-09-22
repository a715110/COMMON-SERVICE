package com.dodaso.ecosystem.common.service.dto;

/**
 * Everything AzureBlobStorageHandler needs to place a blob under the
 * agreed path convention and tag it correctly, without depending on the
 * FileUpload JPA entity. companyId leads the blob path (see
 * AzureBlobStorageHandler's class Javadoc for the tenant-isolation
 * rationale); sourceApp/ownerType/ownerId are the polymorphic owner
 * reference (e.g. sourceApp="ELCM", ownerType="STAGED_DOCUMENT" or
 * sourceApp="ECWS", ownerType="COMMENT" / "ATTACHMENT").
 */
public record BlobUploadContext(Long companyId, String sourceApp, String ownerType, Long ownerId) {
}

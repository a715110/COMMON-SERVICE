package com.dodaso.ecosystem.common.service.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.dodaso.ecosystem.common.dto.BlobUploadContext;
import com.dodaso.ecosystem.common.dto.BlobUploadResult;
import com.dodaso.ecosystem.common.dto.FileUploadRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Real Azure Blob Storage implementation of FileUploadDownloadHandler.
 *
 * <p><b>Step 1 change (dedicated single-tenant / managed identity):</b>
 * BlobServiceClient is now the bean auto-configured by
 * spring-cloud-azure-starter-storage-blob. In the cloud it authenticates with the
 * instance's managed identity (no account key anywhere); locally it uses
 * spring.cloud.azure.storage.blob.connection-string. AzureBlobConfig is removed.
 *
 * <p><b>Required permission for the managed identity:</b> blob index tags
 * (setTags / findBlobsByTags) are a separate sub-resource. The built-in
 * "Storage Blob Data Contributor" role does NOT include
 * .../blobs/tags/write, .../blobs/tags/read or .../blobs/filter/action.
 * The identity needs either "Storage Blob Data Owner" (broader than necessary)
 * or a custom role = Contributor data actions + those three tag actions.
 * Recommended: the custom role, defined in the Step 6 environment template.
 * Under the old account-key auth this never surfaced, because an account key
 * implicitly has every permission.
 *
 * <p>Blob path (unchanged): {companyId}/{ownerType}/{ownerId}/{yyyy}/{MM}/{dd}/{uuid}__{originalFileName}.
 * companyId leads the path so one prefix covers everything a company owns (prefix-scoped
 * SAS, bulk offboarding/purge). sourceApp is carried as a blob index tag, not a path
 * segment. The date is split into folders for Storage Explorer browsability; "__"
 * separates the UUID from the original name because the UUID itself contains hyphens.
 * Container-per-company remains deferred (no concrete driver yet).
 *
 * <p>In the dedicated model the storage account itself is the customer boundary;
 * companyId still leads the path so a customer with several legal entities keeps
 * one prefix per entity.
 *
 * <p>Tags (unchanged): companyId, sourceApp, ownerType, ownerId -- queryable via
 * BlobServiceClient.findBlobsByTags. Requires a general-purpose v2 account without
 * hierarchical namespace. file_upload.company_id in the DB stays the source of truth.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AzureBlobStorageHandler implements FileUploadDownloadHandler {

    private final BlobServiceClient blobServiceClient;

    /** Containers already confirmed to exist in this JVM -- avoids an exists()
     *  round trip on every upload. Containers are normally pre-created by the
     *  environment template; create-if-missing stays as a fallback. */
    private final Set<String> verifiedContainers = ConcurrentHashMap.newKeySet();

    @Override
    public List<BlobUploadResult> uploadFiles(final List<FileUploadRequest> files, final String containerName,
        final BlobUploadContext context) {
        final BlobContainerClient containerClient = getOrCreateContainer(containerName);
        final List<BlobUploadResult> results = new ArrayList<>(files.size());
        final Map<String, String> tags = buildTags(context);

        for (final FileUploadRequest file : files) {
            final String blobName = buildBlobName(file.getFileName(), context);
            final BlobClient blobClient = containerClient.getBlobClient(blobName);

            // One request instead of three (upload + setHttpHeaders + setTags):
            // the blob is never visible without its tags or content type, and a
            // failure can't leave an untagged blob behind. No request conditions
            // => overwrite semantics, same as the previous upload(..., true).
            final BlobParallelUploadOptions options =
                new BlobParallelUploadOptions(BinaryData.fromBytes(file.getContent()))
                    .setTags(tags);
            if (file.getContentType() != null) {
                options.setHeaders(new BlobHttpHeaders().setContentType(file.getContentType()));
            }
            blobClient.uploadWithResponse(options, null, Context.NONE);

            log.info("Uploaded {} ({} bytes) to container {} as blob {} (tags={})",
                file.getFileName(), file.getContent().length, containerName, blobName, tags);

            results.add(new BlobUploadResult(
                file.getFileName(),
                blobName,
                containerName,
                blobClient.getBlobUrl(),   // Step 4: do not persist this -- see note below
                file.getContent().length,
                file.getContentType()));
        }

        return results;
    }

    @Override
    public byte[] downloadFile(final String containerName, final String blobName) {
        final BlobClient blobClient = blobServiceClient.getBlobContainerClient(containerName).getBlobClient(blobName);
        return blobClient.downloadContent().toBytes();
    }

    @Override
    public void deleteFile(final String containerName, final String blobName) {
        final BlobClient blobClient = blobServiceClient.getBlobContainerClient(containerName).getBlobClient(blobName);
        blobClient.deleteIfExists();
    }

    private BlobContainerClient getOrCreateContainer(final String containerName) {
        final BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (verifiedContainers.contains(containerName)) {
            return containerClient;
        }
        if (containerClient.createIfNotExists()) {
            log.warn("Container {} did not exist and was created at runtime -- "
                + "it should be provisioned by the environment template", containerName);
        }
        verifiedContainers.add(containerName);
        return containerClient;
    }

    /** Unchanged: {companyId}/{ownerType}/{ownerId}/{yyyy}/{MM}/{dd}/{uuid}__{originalFileName}. */
    private String buildBlobName(final String originalFileName, final BlobUploadContext context) {
        final java.time.LocalDate today = java.time.LocalDate.now();
        return "%d/%s/%d/%04d/%02d/%02d/%s__%s".formatted(
            context.companyId(), context.ownerType(), context.ownerId(),
            today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
            UUID.randomUUID(), originalFileName);
    }

    /** Unchanged: Azure Blob Index Tags (max 10/blob; all values strings). */
    private Map<String, String> buildTags(final BlobUploadContext context) {
        final Map<String, String> tags = new HashMap<>();
        tags.put("companyId", String.valueOf(context.companyId()));
        tags.put("sourceApp", context.sourceApp());
        tags.put("ownerType", context.ownerType());
        tags.put("ownerId", String.valueOf(context.ownerId()));
        return tags;
    }
}
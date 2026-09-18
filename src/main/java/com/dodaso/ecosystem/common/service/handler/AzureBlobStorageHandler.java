package com.dodaso.ecosystem.common.service.handler;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.dodaso.ecosystem.common.service.dto.BlobUploadContext;
import com.dodaso.ecosystem.common.service.dto.BlobUploadResult;
import com.dodaso.ecosystem.common.service.dto.FileUploadRequest;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Real Azure Blob Storage implementation of FileUploadDownloadHandler.
 * BlobServiceClient itself (account-name/account-key, endpoint) is
 * configured in AzureBlobConfig -- this class only does per-file
 * container/blob operations on top of it.
 *
 * Blob path convention and company tagging, per the container-naming
 * design decided in chat:
 *   - Path: {companyId}/{sourceApp}/{ownerType}/{ownerId}/{yyyy}/{MM}/{dd}/
 *     {uuid}__{originalFileName}. companyId leads the path (not the
 *     container, and not buried mid-path under sourceApp) because it's
 *     the actual tenant-isolation boundary: a SAS token or user-delegation
 *     SAS can be scoped to a path prefix, so "grant/restrict access to
 *     exactly one company's files" and "everything under this company"
 *     bulk operations (offboarding/purge) both reduce to a single prefix
 *     only when companyId is the outermost segment. Putting it after
 *     sourceApp would split one company's data across N app-prefixed
 *     subtrees instead of one.
 *   - Company scoping is NOT limited to the path, though -- every blob is
 *     also tagged (companyId, sourceApp, ownerType, ownerId) via Azure
 *     Blob Index Tags, indexed and queryable directly against Storage
 *     (BlobServiceClient.findBlobsByTags) independent of this service's
 *     own DB. Tags answer cross-cutting queries the path can't (e.g.
 *     "every ELCM file across ALL companies uploaded last week" isn't a
 *     single prefix once companyId leads the path); the path answers the
 *     isolation/bulk-deletion case tags can't do atomically. They're
 *     complementary, not redundant -- the DB (file_upload.company_id)
 *     remains the source of truth for the unified interface's own
 *     queries either way.
 *   - Date is split into {yyyy}/{MM}/{dd} rather than a flat {yyyyMMdd}
 *     token mainly for Storage Explorer/Portal browsability (renders as
 *     nested, drill-down folders rather than one flat token) and human
 *     readability next to the numeric {ownerId} segment -- not because
 *     flat dates can't prefix-match (a zero-padded flat date still would).
 *   - Container-per-company was considered and deliberately deferred, not
 *     rejected outright: it would buy atomic whole-container deletion
 *     (vs. enumerate-and-batch-delete under a path prefix), but adds real
 *     container-management overhead with no concrete driver yet (e.g. a
 *     contractual requirement for physically separate storage). Revisit
 *     if that shows up.
 *   - NOTE: Blob Index Tags require a general-purpose v2 (or premium
 *     block blob) storage account -- assumed true here since it's the
 *     modern default, but not verified against the real account in use;
 *     if tagging calls fail with an unsupported-feature error, that's
 *     the first thing to check.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AzureBlobStorageHandler implements FileUploadDownloadHandler {

    private final BlobServiceClient blobServiceClient;

    @Override
    public List<BlobUploadResult> uploadFiles(final List<FileUploadRequest> files, final String containerName,
                                               final BlobUploadContext context) {
        final BlobContainerClient containerClient = getOrCreateContainer(containerName);
        final List<BlobUploadResult> results = new ArrayList<>(files.size());
        final Map<String, String> tags = buildTags(context);

        for (final FileUploadRequest file : files) {
            final String blobName = buildBlobName(file.getFileName(), context);
            final BlobClient blobClient = containerClient.getBlobClient(blobName);

            blobClient.upload(new ByteArrayInputStream(file.getContent()), file.getContent().length, true);
            if (file.getContentType() != null) {
                blobClient.setHttpHeaders(new BlobHttpHeaders().setContentType(file.getContentType()));
            }
            blobClient.setTags(tags);

            log.info("Uploaded {} ({} bytes) to container {} as blob {} (tags={})",
                file.getFileName(), file.getContent().length, containerName, blobName, tags);

            results.add(new BlobUploadResult(
                file.getFileName(),
                blobName,
                containerName,
                blobClient.getBlobUrl(),
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
        if (!containerClient.exists()) {
            log.info("Container {} does not exist yet -- creating it", containerName);
            containerClient.create();
        }
        return containerClient;
    }

    /** {companyId}/{sourceApp}/{ownerType}/{ownerId}/{yyyy}/{MM}/{dd}/{uuid}__
     * {originalFileName}, per the folder structure decided in chat --
     * companyId leads the path (see class Javadoc for why: it's the
     * actual tenant-isolation boundary, so it has to be the outermost
     * segment for prefix-scoped SAS/bulk-deletion to work against a
     * single prefix). "__" (not "-") separates the UUID from the
     * original file name -- a UUID's own hyphens (8-4-4-4-12) would
     * otherwise make "first dash" an ambiguous split point for anything
     * that later needs to recover the original name from blobName alone. */
    private String buildBlobName(final String originalFileName, final BlobUploadContext context) {
        final java.time.LocalDate today = java.time.LocalDate.now();
        return "%d/%s/%s/%d/%04d/%02d/%02d/%s__%s".formatted(
            context.companyId(), context.sourceApp(), context.ownerType(), context.ownerId(),
            today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
            UUID.randomUUID(), originalFileName);
    }

    /** Azure Blob Index Tags: plain string key/value pairs, indexed and
     * queryable service-side. Max 10 tags/blob, key <=128 chars, value
     * <=256 chars -- comfortably within that here. All values must be
     * strings, hence the manual toString() on the numeric ids. */
    private Map<String, String> buildTags(final BlobUploadContext context) {
        final Map<String, String> tags = new HashMap<>();
        tags.put("companyId", String.valueOf(context.companyId()));
        tags.put("sourceApp", context.sourceApp());
        tags.put("ownerType", context.ownerType());
        tags.put("ownerId", String.valueOf(context.ownerId()));
        return tags;
    }
}

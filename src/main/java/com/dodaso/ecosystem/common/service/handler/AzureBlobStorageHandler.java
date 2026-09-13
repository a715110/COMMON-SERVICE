package com.dodaso.ecosystem.common.service.handler;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.dodaso.ecosystem.common.service.dto.BlobUploadResult;
import com.dodaso.ecosystem.common.service.dto.FileUploadRequest;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Real Azure Blob Storage implementation of FileUploadDownloadHandler.
 * BlobServiceClient itself (account-name/account-key, endpoint) is
 * configured in AzureBlobConfig -- this class only does per-file
 * container/blob operations on top of it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AzureBlobStorageHandler implements FileUploadDownloadHandler {

    private final BlobServiceClient blobServiceClient;

    @Override
    public List<BlobUploadResult> uploadFiles(final List<FileUploadRequest> files, final String containerName) {
        final BlobContainerClient containerClient = getOrCreateContainer(containerName);
        final List<BlobUploadResult> results = new ArrayList<>(files.size());

        for (final FileUploadRequest file : files) {
            final String blobName = buildBlobName(file.getFileName());
            final BlobClient blobClient = containerClient.getBlobClient(blobName);

            blobClient.upload(new ByteArrayInputStream(file.getContent()), file.getContent().length, true);
            if (file.getContentType() != null) {
                blobClient.setHttpHeaders(new BlobHttpHeaders().setContentType(file.getContentType()));
            }

            log.info("Uploaded {} ({} bytes) to container {} as blob {}",
                file.getFileName(), file.getContent().length, containerName, blobName);

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

    private String buildBlobName(final String originalFileName) {
        // "__" (not "-") as the separator between the UUID and the original
        // file name -- a UUID's own hyphens (8-4-4-4-12) would otherwise
        // make "first dash" an ambiguous split point for anything that
        // later needs to recover the original name from blobName alone.
        final java.time.LocalDate today = java.time.LocalDate.now();
        return "%04d/%02d/%02d/%s__%s".formatted(
            today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
            UUID.randomUUID(), originalFileName);
    }
}

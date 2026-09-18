package com.dodaso.ecosystem.common.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dodaso.ecosystem.common.dto.BlobUploadContext;
import com.dodaso.ecosystem.common.dto.BlobUploadResult;
import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.common.dto.FileUploadRequest;
import com.dodaso.ecosystem.common.entity.FileThumbnail;
import com.dodaso.ecosystem.common.entity.FileUpload;
import com.dodaso.ecosystem.common.entity.LkpThumbnailStatus;
import com.dodaso.ecosystem.common.repository.FileThumbnailRepository;
import com.dodaso.ecosystem.common.repository.FileUploadRepository;
import com.dodaso.ecosystem.common.repository.LkpThumbnailStatusRepository;
import com.dodaso.ecosystem.common.service.handler.FileUploadDownloadHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates a file upload end to end: pushes bytes to Azure via
 * FileUploadDownloadHandler, then persists the resulting blob metadata as
 * a FileUpload row -- this is what FileStorageController actually calls,
 * and is the piece elcm-service's own upload flow depends on for "the
 * metadata of the uploaded files should be updated and displayed in the
 * Stage Documents table" (elcm-service reads the returned FileUploadDTO
 * list and stores what it needs against its own staged_document rows;
 * this service does not know about staged_document at all -- ownerType/
 * ownerId here are opaque scalars from elcm-service's point of view).
 *
 * companyId is required now (multi-company support, decided in chat) --
 * it's persisted on the FileUpload row and also passed to the Azure
 * handler as part of BlobUploadContext, which tags each blob with it
 * (Azure Blob Index Tags) rather than encoding it into the container or
 * path -- see AzureBlobStorageHandler's class Javadoc for why.
 *
 * createdBy/updatedBy on the saved rows come from the existing
 * HeaderBasedAuditorAware infrastructure (X-User-Context / X-Remote-User
 * request header), not a parameter here -- see the @CreatedBy/@LastModifiedBy
 * annotations added to FileUpload/FileThumbnail in common-data-model.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileUploadService {

    private static final String THUMBNAIL_PENDING_CODE = "PENDING";

    private final FileUploadDownloadHandler fileUploadDownloadHandler;
    private final FileUploadRepository fileUploadRepository;
    private final FileThumbnailRepository fileThumbnailRepository;
    private final LkpThumbnailStatusRepository lkpThumbnailStatusRepository;

    @Value("${azure.storage.default-container-name:documents}")
    private String defaultContainerName;

    @Transactional
    public List<FileUploadDTO> uploadFiles(final List<FileUploadRequest> files,
                                            final String containerName,
                                            final String sourceApp,
                                            final String ownerType,
                                            final Long ownerId,
                                            final Long companyId) {
        final String resolvedContainer = (containerName != null && !containerName.isBlank())
            ? containerName : defaultContainerName;

        final BlobUploadContext context = new BlobUploadContext(sourceApp, ownerType, ownerId, companyId);
        final List<BlobUploadResult> blobResults = fileUploadDownloadHandler.uploadFiles(files, resolvedContainer, context);

        return blobResults.stream()
            .map(blobResult -> persist(blobResult, sourceApp, ownerType, ownerId, companyId))
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    public FileUploadDTO findById(final Long id) {
        return fileUploadRepository.findById(id)
            .map(this::toDto)
            .orElse(null);
    }

    public List<FileUploadDTO> findByOwner(final Long companyId, final String ownerType, final Long ownerId) {
        return fileUploadRepository.findByCompanyIdAndOwnerTypeAndOwnerIdAndActiveIndTrue(companyId, ownerType, ownerId).stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    public byte[] download(final Long id) {
        final FileUpload fileUpload = fileUploadRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("No file_upload row with id " + id));
        return fileUploadDownloadHandler.downloadFile(fileUpload.getBlobContainer(), fileUpload.getBlobPath());
    }

    /** Soft delete only -- flips active_ind, does not delete the blob or
     * the row. Matches the activeInd-based soft-delete convention already
     * used elsewhere in this codebase rather than hard-deleting; a
     * separate cleanup/retention job, if one's ever needed, is better
     * placed to decide when it's actually safe to remove the blob itself. */
    @Transactional
    public void softDelete(final Long id) {
        fileUploadRepository.findById(id).ifPresent(fileUpload -> {
            fileUpload.setActiveInd(false);
            fileUploadRepository.save(fileUpload);
        });
    }

    private FileUpload persist(final BlobUploadResult blobResult, final String sourceApp,
                                final String ownerType, final Long ownerId, final Long companyId) {
        final FileUpload fileUpload = new FileUpload();
        fileUpload.setSourceApp(sourceApp);
        fileUpload.setOwnerType(ownerType);
        fileUpload.setOwnerId(ownerId);
        fileUpload.setCompanyId(companyId);
        fileUpload.setFileName(blobResult.originalFileName());
        fileUpload.setContentType(blobResult.contentType());
        fileUpload.setFileSize(blobResult.sizeBytes());
        fileUpload.setStorageType("AZURE_BLOB");
        fileUpload.setBlobContainer(blobResult.containerName());
        fileUpload.setBlobPath(blobResult.blobName());
        fileUpload.setBlobUrl(blobResult.url());
        fileUpload.setActiveInd(true);
        final FileUpload saved = fileUploadRepository.save(fileUpload);

        queueThumbnailIfEligible(saved);
        return saved;
    }

    /**
     * Only queues a PENDING file_thumbnail row for image content types, per
     * FileThumbnail's class Javadoc ("a FileUpload with no matching row
     * here was never eligible for a thumbnail"). NOT part of this task's
     * scope: nothing polls/processes PENDING rows yet -- that's a separate
     * worker to build later. If the PENDING lookup row itself hasn't been
     * seeded into lkp_thumbnail_status, this logs and skips rather than
     * failing the upload over missing seed data unrelated to the file
     * actually having been stored successfully.
     */
    private void queueThumbnailIfEligible(final FileUpload fileUpload) {
        if (fileUpload.getContentType() == null || !fileUpload.getContentType().startsWith("image/")) {
            return;
        }

        final Optional<LkpThumbnailStatus> pending = lkpThumbnailStatusRepository.findByCode(THUMBNAIL_PENDING_CODE);
        if (pending.isEmpty()) {
            log.warn("Skipping thumbnail queue for file_upload id={}: no '{}' row seeded in lkp_thumbnail_status",
                fileUpload.getId(), THUMBNAIL_PENDING_CODE);
            return;
        }

        final FileThumbnail thumbnail = new FileThumbnail();
        thumbnail.setFileUpload(fileUpload);
        thumbnail.setStatus(pending.get());
        thumbnail.setAttemptCount(0);
        thumbnail.setRequestedAt(LocalDateTime.now());
        fileThumbnailRepository.save(thumbnail);
    }

    private FileUploadDTO toDto(final FileUpload entity) {
        final FileUploadDTO dto = new FileUploadDTO();
        dto.setId(entity.getId());
        dto.setSourceApp(entity.getSourceApp());
        dto.setOwnerType(entity.getOwnerType());
        dto.setOwnerId(entity.getOwnerId());
        dto.setCompanyId(entity.getCompanyId());
        dto.setFileName(entity.getFileName());
        dto.setContentType(entity.getContentType());
        dto.setFileSize(entity.getFileSize());
        dto.setStorageType(entity.getStorageType());
        dto.setBlobContainer(entity.getBlobContainer());
        dto.setBlobPath(entity.getBlobPath());
        dto.setBlobUrl(entity.getBlobUrl());
        dto.setActiveInd(entity.getActiveInd());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}

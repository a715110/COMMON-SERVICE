package com.dodaso.ecosystem.common.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dodaso.ecosystem.common.dto.BlobUploadContext;
import com.dodaso.ecosystem.common.dto.BlobUploadResult;
import com.dodaso.ecosystem.common.dto.FileThumbnailDTO;
import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.common.dto.FileUploadRequest;
import com.dodaso.ecosystem.common.dto.LkpThumbnailStatusDTO;
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
 *
 * Every upload gets a file_thumbnail row queued (see queueThumbnail()) --
 * the request returns as soon as file_upload/file_thumbnail(PENDING) are
 * committed, without waiting for the thumbnail image itself. The client
 * is expected to render a generic per-type placeholder immediately from
 * file_upload.content_type alone and swap in the real thumbnail once
 * ThumbnailGenerationService finishes and a later poll reports COMPLETED.
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
    private final ThumbnailGenerationService thumbnailGenerationService;

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
                ? containerName
                : defaultContainerName;

        final BlobUploadContext context = new BlobUploadContext(sourceApp, ownerType, ownerId, companyId);
        final List<BlobUploadResult> blobResults = fileUploadDownloadHandler.uploadFiles(files, resolvedContainer,
                context);

        // Zipped by index rather than mapped independently: persist() needs each
        // blob's ORIGINAL bytes (still held here in `files`) to hand off to
        // thumbnail generation without a redundant re-download from Azure --
        // see queueThumbnail()'s Javadoc. This relies on
        // FileUploadDownloadHandler.uploadFiles() returning results in the same
        // order as the input list, which is part of its documented contract.
        return IntStream.range(0, blobResults.size())
                .mapToObj(i -> persist(blobResults.get(i), files.get(i).getContent(), sourceApp, ownerType, ownerId,
                        companyId))
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public Optional<FileUploadDTO> findById(final Long id) {
        return fileUploadRepository.findById(id).map(this::toDto);
    }

    public List<FileUploadDTO> findByOwner(final Long companyId, final String ownerType, final Long ownerId) {
        return fileUploadRepository.findByCompanyIdAndOwnerTypeAndOwnerIdAndActiveIndTrue(companyId, ownerType, ownerId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public byte[] download(final Long id) {
        final FileUpload fileUpload = fileUploadRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No file_upload row with id " + id));
        return fileUploadDownloadHandler.downloadFile(fileUpload.getBlobContainer(), fileUpload.getBlobPath());
    }

    /**
     * Metadata for the thumbnail belonging to a file_upload row -- status
     * code, dimensions, timestamps, last error -- for a UI to poll ("is it
     * ready yet?") without pulling image bytes. Empty means no
     * file_thumbnail row exists at all, which -- now that queueThumbnail()
     * queues one for every upload -- should only happen for a file_upload
     * row that predates this behavior, or if seeding lkp_thumbnail_status
     * was missing at upload time (see queueThumbnail()'s Javadoc). A
     * present DTO with status PENDING/PROCESSING/FAILED means "not ready,
     * check back or show the failure", and COMPLETED means
     * downloadThumbnail() below will return bytes.
     */
    public Optional<FileThumbnailDTO> getThumbnailStatus(final Long fileUploadId) {
        return fileThumbnailRepository.findByFileUpload_Id(fileUploadId).map(this::toThumbnailDto);
    }

    /**
     * Thumbnail image bytes for a file_upload row, if -- and only if --
     * generation has reached COMPLETED. Returns empty (never throws) for
     * "no thumbnail row", "still PENDING/PROCESSING", and "FAILED", since
     * all three are the same "not available right now" case to a caller;
     * getThumbnailStatus() is how a caller distinguishes them.
     */
    public Optional<byte[]> downloadThumbnail(final Long fileUploadId) {
        return fileThumbnailRepository.findByFileUpload_Id(fileUploadId)
                .filter(thumbnail -> thumbnail.getStatus() != null
                        && ThumbnailGenerationService.THUMBNAIL_COMPLETED_CODE.equals(thumbnail.getStatus().getCode())
                        && thumbnail.getBlobPath() != null)
                .map(thumbnail -> fileUploadDownloadHandler.downloadFile(thumbnail.getBlobContainer(),
                        thumbnail.getBlobPath()));
    }

    /**
     * Soft delete only -- flips active_ind, does not delete the blob or
     * the row. Matches the activeInd-based soft-delete convention already
     * used elsewhere in this codebase rather than hard-deleting; a
     * separate cleanup/retention job, if one's ever needed, is better
     * placed to decide when it's actually safe to remove the blob itself.
     */
    @Transactional
    public void softDelete(final Long id) {
        fileUploadRepository.findById(id).ifPresent(fileUpload -> {
            fileUpload.setActiveInd(false);
            fileUploadRepository.save(fileUpload);
        });
    }

    private FileUpload persist(final BlobUploadResult blobResult, final byte[] originalContent,
            final String sourceApp, final String ownerType, final Long ownerId, final Long companyId) {
        final FileUpload fileUpload = new FileUpload();
        fileUpload.setSourceApp(sourceApp);
        fileUpload.setOwnerType(ownerType);
        fileUpload.setOwnerId(ownerId);
        fileUpload.setCompanyId(companyId);
        fileUpload.setFileName(blobResult.fileName());
        fileUpload.setContentType(blobResult.contentType());
        fileUpload.setFileSize(blobResult.fileSize());
        fileUpload.setStorageType("AZURE_BLOB");
        fileUpload.setBlobContainer(blobResult.blobContainer());
        fileUpload.setBlobPath(blobResult.blobPath());
        fileUpload.setBlobUrl(blobResult.blobUrl());
        fileUpload.setActiveInd(true);
        final FileUpload saved = fileUploadRepository.save(fileUpload);

        queueThumbnail(saved, originalContent);
        return saved;
    }

    /**
     * Queues a PENDING file_thumbnail row for EVERY upload -- not just
     * images -- then registers an afterCommit() callback that hands off
     * to ThumbnailGenerationService.generateThumbnailAsync() once the
     * enclosing transaction actually commits. What generateThumbnailAsync()
     * does with the row differs by format (a real preview for PDF/DOCX/
     * image via thumbnails4j, a generated representative icon for
     * anything else) -- see its class Javadoc -- but every upload ends up
     * with exactly one file_thumbnail row, so callers never need to ask
     * "does this file even get a thumbnail?"
     *
     * (Renamed from queueThumbnailIfEligible(): "if eligible" no longer
     * describes this method now that unsupported formats are queued too,
     * just handled differently once picked up.)
     *
     * If the PENDING lookup row itself hasn't been seeded into
     * lkp_thumbnail_status, this logs and skips rather than failing the
     * upload over missing seed data unrelated to the file actually having
     * been stored successfully.
     *
     * originalContent is the SAME byte[] persist() received from the
     * upload request -- passed through rather than re-read here.
     * afterCommit() callbacks run synchronously, on the request thread,
     * before the transactional proxy returns control to the caller (i.e.
     * before the HTTP response goes out) -- so calling
     * fileUploadDownloadHandler.downloadFile() from inside afterCommit()
     * to re-fetch bytes already sitting in memory would add a needless
     * Azure round trip to the client-visible response time. Threading the
     * bytes through instead keeps that callback doing only two things:
     * dispatch to the thumbnail-gen-* pool via generateThumbnailAsync()'s
     * own @Async, and nothing else.
     */
    private void queueThumbnail(final FileUpload fileUpload, final byte[] originalContent) {
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
        final FileThumbnail savedThumbnail = fileThumbnailRepository.save(thumbnail);

        // Register callback to trigger async thumbnail generation AFTER main DB
        // commit.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // Executed only if uploadFiles() transaction commits successfully.
                    // generateThumbnailAsync() is itself @Async and dispatches to the
                    // THUMBNAIL_EXECUTOR_BEAN thread pool -- everything from here on
                    // runs off the request thread.
                    thumbnailGenerationService.generateThumbnailAsync(
                            savedThumbnail.getId(),
                            fileUpload.getId(),
                            originalContent,
                            fileUpload.getContentType(),
                            fileUpload.getFileName(),
                            fileUpload.getBlobContainer(),
                            new BlobUploadContext(fileUpload.getSourceApp(), fileUpload.getOwnerType(),
                                    fileUpload.getOwnerId(), fileUpload.getCompanyId()));
                }
            });
        } else {
            // No active transaction synchronization (e.g. a direct unit-test call
            // to persist()) -- fire immediately rather than silently dropping the
            // thumbnail.
            thumbnailGenerationService.generateThumbnailAsync(
                    savedThumbnail.getId(),
                    fileUpload.getId(),
                    originalContent,
                    fileUpload.getContentType(),
                    fileUpload.getFileName(),
                    fileUpload.getBlobContainer(),
                    new BlobUploadContext(fileUpload.getSourceApp(), fileUpload.getOwnerType(),
                            fileUpload.getOwnerId(), fileUpload.getCompanyId()));
        }
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

    private FileThumbnailDTO toThumbnailDto(final FileThumbnail entity) {
        final FileThumbnailDTO dto = new FileThumbnailDTO();
        dto.setId(entity.getId());
        if (entity.getFileUpload() != null) {
            dto.setFileUploadDTO(toDto(entity.getFileUpload()));
        }
        if (entity.getStatus() != null) {
            dto.setStatusDTO(toStatusDto(entity.getStatus()));
        }
        dto.setBlobContainer(entity.getBlobContainer());
        dto.setBlobPath(entity.getBlobPath());
        dto.setBlobUrl(entity.getBlobUrl());
        dto.setWidthPx(entity.getWidthPx());
        dto.setHeightPx(entity.getHeightPx());
        dto.setAttemptCount(entity.getAttemptCount());
        dto.setLastErrorMessage(entity.getLastErrorMessage());
        dto.setRequestedAt(entity.getRequestedAt());
        dto.setStartedAt(entity.getStartedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private LkpThumbnailStatusDTO toStatusDto(final LkpThumbnailStatus entity) {
        final LkpThumbnailStatusDTO dto = new LkpThumbnailStatusDTO();
        dto.setId(entity.getId());
        dto.setCode(entity.getCode());
        dto.setLabel(entity.getLabel());
        dto.setDescription(entity.getDescription());
        dto.setSortOrder(entity.getSortOrder());
        dto.setIsActive(entity.getIsActive());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

}

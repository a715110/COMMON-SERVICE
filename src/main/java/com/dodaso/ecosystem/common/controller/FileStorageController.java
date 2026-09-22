package com.dodaso.ecosystem.common.controller;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dodaso.ecosystem.common.container.FileUploadDTOContainer;
import com.dodaso.ecosystem.common.dto.FileItemDTO;
import com.dodaso.ecosystem.common.dto.FileThumbnailDTO;
import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.common.dto.FileUploadRequest;
import com.dodaso.ecosystem.common.dto.FileUploadRequestDTO;
import com.dodaso.ecosystem.common.service.FileUploadService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared file upload/download/delete endpoint, used by elcm-service (and,
 * eventually, ecws-service) rather than being ELCM- or ECWS-specific --
 * that's why ownerType/ownerId/sourceApp/companyId are opaque caller-
 * supplied scalars rather than anything ELCM-domain-shaped (e.g. no
 * reference to "staged document" anywhere here). See FileUploadService's
 * class Javadoc for the full upload flow (Azure transfer + FileUpload row
 * persistence, and how companyId flows into Azure Blob Index Tags).
 *
 * IMPORTANT: /upload takes a plain JSON body (FileUploadRequestDTO), NOT
 * multipart/form-data, even though the request carries raw file bytes.
 * This was deliberately changed from an earlier multipart-based version --
 * every existing cross-service call in this codebase (PipelineMetricsBean,
 * UserHelper) goes through RESTServiceClient/RESTReqContainer, which posts
 * a JSON DTO body, not multipart. Jackson serializes/deserializes byte[]
 * fields as base64 strings automatically, so FileItemDTO.content just
 * works as a normal JSON field -- no multipart parsing needed on this
 * side. The browser-to-elcm-ui hop is unaffected: that's still a real
 * multipart post via p:fileUpload; this endpoint only describes the
 * second hop (elcm-ui forwarding already-received bytes on to here).
 *
 * Returns FileUploadDTOContainer, per the common-data-model DataContainer
 * convention already used by ecws-data-model/elcm-data-model -- a single
 * DTO field for single-record responses, a list field for multi-record
 * ones -- rather than a bare List/DTO, so this matches how elcm-service's
 * own controllers already shape their responses.
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Slf4j
public class FileStorageController {

    private final FileUploadService fileUploadService;

    /**
     * containerName is optional on the request DTO (falls back to
     * azure.storage.default-container-name); sourceApp/ownerType/ownerId/
     * companyId are expected -- these are what let a later "list files
     * for this record" query (GET /owner) find them again, and what get
     * set as this blob's Azure Blob Index Tags.
     */
    @PostMapping(value = "/upload", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FileUploadDTOContainer> upload(@RequestBody final FileUploadDTOContainer uploadDTOContainer) {
        FileUploadRequestDTO request = uploadDTOContainer.getFileUploadRequestDTO();
        
        final List<FileItemDTO> files = request.getFiles();

        final List<FileUploadRequest> requests = (files == null ? List.<FileItemDTO>of() : files).stream()
                .map(item -> new FileUploadRequest(item.getFileName(), item.getContentType(), item.getContent()))
                .collect(Collectors.toList());

        log.info("Uploading {} file(s) for {}/{} (sourceApp={}, companyId={})",
                files != null ? files.size() : 0, request.getOwnerType(), request.getOwnerId(), request.getSourceApp(),
                request.getCompanyId());
        final List<FileUploadDTO> uploaded = fileUploadService.uploadFiles(
                requests, request.getContainerName(), request.getSourceApp(), request.getOwnerType(),
                request.getOwnerId(), request.getCompanyId());

        final FileUploadDTOContainer container = new FileUploadDTOContainer();
        container.setFileUploadDTOList(uploaded);
        return ResponseEntity.ok(container);
    }

    // @GetMapping("/{id}")
    // public ResponseEntity<FileUploadDTOContainer> getById(@PathVariable final
    // Long id) {
    // final FileUploadDTO dto = fileUploadService.findById(id);
    // if (dto == null) {
    // return ResponseEntity.notFound().build();
    // }
    // final FileUploadDTOContainer container = new FileUploadDTOContainer();
    // container.setFileUploadDTO(dto);
    // return ResponseEntity.ok(container);
    // }

    /**
     * e.g. GET /api/v1/files/owner?companyId=7&ownerType=STAGED_DOCUMENT&ownerId=42
     * to list every file attached to one elcm-service record. companyId
     * is required, not optional -- see FileUploadRepository's finder for
     * why ownerType/ownerId alone aren't a safe-enough scope once this
     * table spans multiple companies.
     */
    // @GetMapping("/owner")
    // public ResponseEntity<FileUploadDTOContainer> getByOwner(
    // @RequestParam final Long companyId,
    // @RequestParam final String ownerType,
    // @RequestParam final Long ownerId) {

    // final FileUploadDTOContainer container = new FileUploadDTOContainer();
    // container.setFileUploadDTOList(fileUploadService.findByOwner(companyId,
    // ownerType, ownerId));
    // return ResponseEntity.ok(container);
    // }

    @GetMapping("/{id}")
    public ResponseEntity<FileUploadDTO> getById(@PathVariable final Long id) {
        return fileUploadService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/owner")
    public List<FileUploadDTO> byOwner(@RequestParam final Long companyId, @RequestParam final String ownerType,
            @RequestParam final Long ownerId) {
        return fileUploadService.findByOwner(companyId, ownerType, ownerId);
    }

   @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable final Long id) {
        final FileUploadDTO fileUploadDTO = fileUploadService.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("No file_upload row for id=" + id));
        final byte[] content = fileUploadService.download(id);

        final MediaType mediaType = fileUploadDTO.getContentType() != null
            ? MediaType.parseMediaType(fileUploadDTO.getContentType())
            : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileUploadDTO.getFileName() + "\"")
            .contentType(mediaType)
            .body(content);
    }

    /** Soft delete only -- see FileUploadService.softDelete() for why. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable final Long id) {
        fileUploadService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Metadata for the thumbnail belonging to file_upload id -- status
     * code (PENDING/PROCESSING/COMPLETED/FAILED), dimensions, timestamps,
     * lastErrorMessage on failure. Intended for a UI to poll after upload
     * (generation is async and won't be ready immediately) before it
     * bothers requesting the actual image bytes below. 404 means the file
     * was never eligible for a thumbnail at all (e.g. non-image content
     * type) -- there is no file_thumbnail row to report on.
     */
    @GetMapping("/{id}/thumbnail/status")
    public ResponseEntity<FileThumbnailDTO> thumbnailStatus(@PathVariable final Long id) {
        return fileUploadService.getThumbnailStatus(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Streams the generated thumbnail image for a file_upload row. Added
     * alongside thumbnail generation itself: file_upload.blob_url /
     * file_thumbnail.blob_url point at blobs in a private container, so
     * neither can be dropped straight into a browser <img src> -- this
     * endpoint is the only way a UI can actually render a thumbnail.
     * Returns 404 if the file was never eligible for a thumbnail, and 202
     * (Accepted, no body) if generation is still PENDING/PROCESSING or
     * previously FAILED -- callers should treat both as "not ready yet"
     * and fall back to a placeholder icon rather than treating them as
     * errors; thumbnailStatus() above is how a caller tells "still
     * working" apart from "failed".
     */
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable final Long id) {
        final Optional<FileThumbnailDTO> statusDto = fileUploadService.getThumbnailStatus(id);
        if (statusDto.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return fileUploadService.downloadThumbnail(id)
                .map(content -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(content))
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

}

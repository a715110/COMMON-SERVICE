package com.dodaso.ecosystem.common.controller;

import com.dodaso.ecosystem.common.container.FileUploadDTOContainer;
import com.dodaso.ecosystem.common.dto.FileUploadDTO;
import com.dodaso.ecosystem.common.service.FileUploadService;
import com.dodaso.ecosystem.common.service.dto.FileUploadRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Shared file upload/download/delete endpoint, used by elcm-service (and,
 * eventually, ecws-service) rather than being ELCM- or ECWS-specific --
 * that's why ownerType/ownerId/sourceApp are opaque caller-supplied
 * scalars rather than anything ELCM-domain-shaped (e.g. no reference to
 * "staged document" anywhere here). See FileUploadService's class Javadoc
 * for the full upload flow (Azure transfer + FileUpload row persistence).
 *
 * Returns/accepts FileUploadDTOContainer, per the common-data-model
 * DataContainer convention already used by ecws-data-model/elcm-data-model
 * -- a single DTO field for single-record responses, a list field for
 * multi-record ones -- rather than a bare List/DTO, so this matches how
 * elcm-service's own controllers already shape their responses.
 */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Slf4j
public class FileStorageController {

    private final FileUploadService fileUploadService;

    /**
     * Multiple files in one request -- matches ELCM UI's Upload Files
     * dialog, which queues several files client-side (p:fileUpload,
     * multiple="true") before submitting. "files" as the multipart field
     * name is the conventional one Spring's MultipartFile[] binding
     * expects; the caller (elcm-service, relaying from elcm-ui) must post
     * each file under that same field name.
     *
     * containerName is optional (falls back to
     * azure.storage.default-container-name); sourceApp/ownerType/ownerId
     * are required -- these are what let a later "list files for this
     * record" query (GET /owner) find them again.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadDTOContainer> upload(
        @RequestParam("files") final MultipartFile[] files,
        @RequestParam final String sourceApp,
        @RequestParam final String ownerType,
        @RequestParam final Long ownerId,
        @RequestParam(required = false) final String containerName) {

        final List<FileUploadRequest> requests = new ArrayList<>(files.length);
        for (final MultipartFile file : files) {
            try {
                requests.add(new FileUploadRequest(file.getOriginalFilename(), file.getContentType(), file.getBytes()));
            } catch (final IOException e) {
                // One unreadable file shouldn't be swallowed silently --
                // surfacing as a 500 for the whole batch is deliberate here
                // rather than skipping the bad file and partially
                // succeeding, since the caller expects a result entry per
                // file it sent.
                throw new UncheckedIOException("Failed to read uploaded file " + file.getOriginalFilename(), e);
            }
        }

        log.info("Uploading {} file(s) for {}/{} (sourceApp={})", requests.size(), ownerType, ownerId, sourceApp);
        final List<FileUploadDTO> uploaded = fileUploadService.uploadFiles(
            requests, containerName, sourceApp, ownerType, ownerId);

        final FileUploadDTOContainer container = new FileUploadDTOContainer();
        container.setFileUploadDTOList(uploaded);
        return ResponseEntity.ok(container);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FileUploadDTOContainer> getById(@PathVariable final Long id) {
        final FileUploadDTO dto = fileUploadService.findById(id);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        final FileUploadDTOContainer container = new FileUploadDTOContainer();
        container.setFileUploadDTO(dto);
        return ResponseEntity.ok(container);
    }

    /** e.g. GET /api/v1/files/owner?ownerType=STAGED_DOCUMENT&ownerId=42
     * to list every file attached to one elcm-service record. */
    @GetMapping("/owner")
    public ResponseEntity<FileUploadDTOContainer> getByOwner(
        @RequestParam final String ownerType,
        @RequestParam final Long ownerId) {

        final FileUploadDTOContainer container = new FileUploadDTOContainer();
        container.setFileUploadDTOList(fileUploadService.findByOwner(ownerType, ownerId));
        return ResponseEntity.ok(container);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable final Long id) {
        final FileUploadDTO metadata = fileUploadService.findById(id);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }

        final byte[] content = fileUploadService.download(id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + metadata.getFileName() + "\"")
            .contentType(metadata.getContentType() != null
                ? MediaType.parseMediaType(metadata.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM)
            .body(content);
    }

    /** Soft delete only -- see FileUploadService.softDelete() for why. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable final Long id) {
        fileUploadService.softDelete(id);
        return ResponseEntity.noContent().build();
    }
}

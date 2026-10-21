package com.dodaso.ecosystem.common.service;

import com.dodaso.ecosystem.common.entity.DocumentConversion;
import com.dodaso.ecosystem.common.entity.FileThumbnail;
import com.dodaso.ecosystem.common.entity.FileUpload;
import com.dodaso.ecosystem.common.repository.DocumentConversionRepository;
import com.dodaso.ecosystem.common.repository.FileThumbnailRepository;
import com.dodaso.ecosystem.common.repository.FileUploadRepository;
import com.dodaso.ecosystem.common.service.handler.FileUploadDownloadHandler;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADDED 2026-10-21. Removes one file for good: the blob, the thumbnail blob, the converted
 * preview blob, then the thumbnail, conversion and file_upload rows. It is a separate bean so the
 * transaction of {@link #purge(Long, String)} applies when {@link FilePurgeService} calls it.
 *
 * <p>The blobs go first and the rows last. If a blob cannot be deleted the exception rolls the
 * transaction back and the rows stay, so the next run tries again. A blob that is already gone is
 * not an error ({@code deleteFile} uses deleteIfExists).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilePurger {

  /** What happened to one id. */
  public enum Outcome {
    /** The file and everything that belonged to it is removed. */
    PURGED,
    /** There was no such file, so there is nothing left to remove. */
    NOT_FOUND,
    /** The file belongs to another source app than the caller named; left alone. */
    SKIPPED_OTHER_SOURCE
  }

  private final FileUploadRepository fileUploadRepository;
  private final FileThumbnailRepository fileThumbnailRepository;
  private final DocumentConversionRepository documentConversionRepository;
  private final FileUploadDownloadHandler blobHandler;

  /**
   * Removes the file.
   *
   * @param id file_upload id
   * @param requiredSourceApp when not blank, only a file of this source app is removed
   */
  @Transactional
  public Outcome purge(final Long id, final String requiredSourceApp) {
    final Optional<FileUpload> found = fileUploadRepository.findById(id);
    if (found.isEmpty()) {
      return Outcome.NOT_FOUND;
    }
    final FileUpload file = found.get();
    if (requiredSourceApp != null && !requiredSourceApp.isBlank()
        && !requiredSourceApp.equalsIgnoreCase(file.getSourceApp())) {
      log.warn("PURGE file_upload {} not removed: source app is {}, caller named {}", id,
          file.getSourceApp(), requiredSourceApp);
      return Outcome.SKIPPED_OTHER_SOURCE;
    }

    final Optional<FileThumbnail> thumbnail = fileThumbnailRepository.findByFileUpload_Id(id);
    final Optional<DocumentConversion> conversion =
        documentConversionRepository.findByFileUpload_Id(id);

    deleteBlob(file.getBlobContainer(), file.getBlobPath());
    thumbnail.ifPresent(t -> deleteBlob(t.getBlobContainer(), t.getBlobPath()));
    conversion.ifPresent(c -> deleteBlob(c.getBlobContainer(), c.getBlobPath()));

    thumbnail.ifPresent(fileThumbnailRepository::delete);
    conversion.ifPresent(documentConversionRepository::delete);
    fileUploadRepository.flush();
    fileUploadRepository.delete(file);
    log.info("PURGE file_upload {} ({}) removed", id, file.getFileName());
    return Outcome.PURGED;
  }

  private void deleteBlob(final String container, final String path) {
    if (container != null && !container.isBlank() && path != null && !path.isBlank()) {
      blobHandler.deleteFile(container, path);
    }
  }
}

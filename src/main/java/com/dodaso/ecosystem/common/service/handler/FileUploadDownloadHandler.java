package com.dodaso.ecosystem.common.service.handler;

import java.util.List;

import com.dodaso.ecosystem.common.dto.BlobUploadContext;
import com.dodaso.ecosystem.common.dto.BlobUploadResult;
import com.dodaso.ecosystem.common.dto.FileUploadRequest;

/**
 * Contract for moving file bytes to/from wherever this service actually
 * stores them. Deliberately knows nothing about FileUpload/persistence --
 * that's FileUploadService's job, composing this handler with the
 * repositories. AzureBlobStorageHandler is the only implementation today;
 * callers depend on this interface so a different backing store could be
 * swapped in later without touching FileUploadService.
 */
public interface FileUploadDownloadHandler {

    /** Uploads one or more files into containerName, creating the container
     * if it doesn't already exist. context supplies the batch-level info
     * (sourceApp/ownerType/ownerId/companyId) used to build each blob's
     * path -- {companyId}/{sourceApp}/{ownerType}/{ownerId}/{yyyy}/{MM}/{dd}/
     * {uuid}__{fileName} -- and its Blob Index Tags. Returns one result per
     * input file, in the same order. */
    List<BlobUploadResult> uploadFiles(List<FileUploadRequest> files, String containerName, BlobUploadContext context);

    /** Raw bytes of a previously uploaded file. */
    byte[] downloadFile(String containerName, String blobName);

    /** No-op if the blob doesn't exist -- deleting something already gone
     * isn't an error condition for a caller retrying a cleanup. */
    void deleteFile(String containerName, String blobName);
}

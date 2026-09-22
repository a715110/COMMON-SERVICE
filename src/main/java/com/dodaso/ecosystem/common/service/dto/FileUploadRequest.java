package com.dodaso.ecosystem.common.service.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Internal, service-layer-only carrier for one file's raw bytes plus its
 * declared name/content-type, as handed to FileUploadDownloadHandler. Kept
 * in this module's own service.dto package (not common.dto) so it never
 * collides with common-data-model's public FileUploadDTO-family classes --
 * those live in the same base package name but a different jar, and a
 * split package across artifacts is worth avoiding even though the JVM
 * would tolerate it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadRequest {

    private String fileName;
    private String contentType;
    private byte[] content;
}

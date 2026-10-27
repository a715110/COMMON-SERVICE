package com.dodaso.ecosystem.common.security;

/**
 * Catalog of common-service permissions, {@code area:action} in lower case. common-service is
 * shared by the products, so these describe file handling only; product specific rules stay in
 * the product services.
 */
public enum CommonPermission {

  /** Read file metadata, download, thumbnails and converted previews. */
  FILE_VIEW("file:view"),

  /** Upload files and request a conversion retry. */
  FILE_UPLOAD("file:upload"),

  /** Delete files. */
  FILE_DELETE("file:delete"),

  /** ADDED 2026-10-21. Permanently remove files, their blobs, thumbnails and previews. */
  FILE_PURGE("file:purge"),

  /**
   * ADDED 2026-10-27. Read and change the messaging channel settings (Teams, Slack, email and
   * the secrets behind them) and test or reset them.
   */
  MESSAGING_ADMIN("messaging:admin");

  private final String code;

  CommonPermission(String code) {
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}

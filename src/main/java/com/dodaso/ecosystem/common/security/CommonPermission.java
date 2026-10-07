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
  FILE_DELETE("file:delete");

  private final String code;

  CommonPermission(String code) {
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}

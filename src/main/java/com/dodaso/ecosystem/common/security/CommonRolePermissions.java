package com.dodaso.ecosystem.common.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Default mapping of IAMS role names to file permissions. A starting point to review; it is the
 * one place to change until the role and permission editor replaces it. Upload follows the ELCM
 * rule for adding documents to the pipeline, delete is limited to the admin roles. Role names
 * are matched case insensitively, exactly as stored in IAMS.
 *
 * <p>REVISED 2026-10-27: MESSAGING_ADMIN (messaging channel settings) goes to Super Admin and
 * System Admin only, because they get every permission; no other role is given it.
 *
 * <p>ADDED 2026-11-06: {@link #SERVICE_ROLE} is not an IAMS role. It is given to a listed service
 * (see {@link AccessTokenFilter}) for background work with no user, and may only view files.
 */
public final class CommonRolePermissions {

  /** Role of a trusted platform service calling without a user. Never comes from IAMS. */
  public static final String SERVICE_ROLE = "Platform Service";

  private static final Map<String, Set<CommonPermission>> BY_ROLE = new HashMap<>();

  static {
    Set<CommonPermission> all = EnumSet.allOf(CommonPermission.class);
    Set<CommonPermission> viewOnly = EnumSet.of(CommonPermission.FILE_VIEW);
    Set<CommonPermission> viewAndUpload =
        EnumSet.of(CommonPermission.FILE_VIEW, CommonPermission.FILE_UPLOAD);

    put(SERVICE_ROLE, viewOnly);
    put("Super Admin", all);
    put("System Admin", all);
    put("Document Submitter", viewAndUpload);
    put("Business Submitter", viewAndUpload);
    put("Preparer", viewAndUpload);
    put("Reviewer", viewOnly);
    put("Approver", viewOnly);
    put("Accountant", viewOnly);
    put("Controller", viewOnly);
    put("Auditor", viewOnly);
  }

  private CommonRolePermissions() {}

  private static void put(String role, Set<CommonPermission> permissions) {
    BY_ROLE.put(role.toLowerCase(Locale.ROOT), permissions);
  }

  /** True when any of the given roles grants the permission. Unknown roles grant nothing. */
  public static boolean isGranted(Collection<String> roleNames, CommonPermission permission) {
    if (roleNames == null) {
      return false;
    }
    for (String role : roleNames) {
      if (role == null) {
        continue;
      }
      Set<CommonPermission> granted = BY_ROLE.get(role.trim().toLowerCase(Locale.ROOT));
      if (granted != null && granted.contains(permission)) {
        return true;
      }
    }
    return false;
  }
}

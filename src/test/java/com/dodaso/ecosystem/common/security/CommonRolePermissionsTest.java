package com.dodaso.ecosystem.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** ADDED 2026-10-27. Who may change the messaging channel settings. */
class CommonRolePermissionsTest {

  @Test
  void adminsMayAdministerMessaging() {
    assertThat(CommonRolePermissions.isGranted(
        List.of("System Admin"), CommonPermission.MESSAGING_ADMIN)).isTrue();
    assertThat(CommonRolePermissions.isGranted(
        List.of("super admin"), CommonPermission.MESSAGING_ADMIN)).isTrue();
  }

  @Test
  void otherRolesMayNot() {
    for (final String role : List.of("Document Submitter", "Business Submitter", "Preparer",
        "Reviewer", "Approver", "Accountant", "Controller", "Auditor", "Unknown Role")) {
      assertThat(CommonRolePermissions.isGranted(
          List.of(role), CommonPermission.MESSAGING_ADMIN)).as(role).isFalse();
    }
  }

  @Test
  void noRolesMeansNoPermission() {
    assertThat(CommonRolePermissions.isGranted(null, CommonPermission.MESSAGING_ADMIN)).isFalse();
    assertThat(CommonRolePermissions.isGranted(List.of(), CommonPermission.MESSAGING_ADMIN))
        .isFalse();
  }
}

package com.dodaso.ecosystem.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.dodaso.ecosystem.baseline.common.security.JwtTokenUtil;
import jakarta.servlet.FilterChain;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

/** ADDED 2026-11-06. A listed service may view files without a user; nobody else gains anything. */
class AccessTokenFilterServiceCallerTest {

  private static final String SECRET =
      "dGhpcy1pcy1hLXRlc3Qtc2VjcmV0LXRoYXQtaXMtbG9uZy1lbm91Z2gtZm9yLWhtYWM1MTI=";

  private JwtTokenUtil tokens;
  private AccessTokenFilter filter;

  @BeforeEach
  void setUp() {
    tokens = new JwtTokenUtil();
    ReflectionTestUtils.setField(tokens, "secret", SECRET);
    ReflectionTestUtils.setField(tokens, "expiration", 60_000L);
    filter =
        new AccessTokenFilter(
            mock(JwtDecoder.class), false, tokens, Set.of("svc:elcm_service"));
  }

  private VerifiedCaller run(String authorization, boolean required) throws Exception {
    AccessTokenFilter f =
        required
            ? new AccessTokenFilter(mock(JwtDecoder.class), true, tokens, Set.of("svc:elcm_service"))
            : filter;
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/142/download");
    if (authorization != null) {
      request.addHeader("Authorization", authorization);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    f.doFilter(request, response, mock(FilterChain.class));
    Object caller = request.getAttribute(VerifiedCaller.ATTRIBUTE);
    return caller instanceof VerifiedCaller vc ? vc : null;
  }

  @Test
  void listedServiceBecomesPlatformServiceWithFileView() throws Exception {
    VerifiedCaller caller = run("Bearer " + tokens.generateToken("svc:elcm_service"), false);
    assertThat(caller).isNotNull();
    assertThat(caller.loginId()).isEqualTo("svc:elcm_service");
    assertThat(CommonRolePermissions.isGranted(caller.roles(), CommonPermission.FILE_VIEW)).isTrue();
    assertThat(CommonRolePermissions.isGranted(caller.roles(), CommonPermission.FILE_DELETE))
        .isFalse();
    assertThat(CommonRolePermissions.isGranted(caller.roles(), CommonPermission.MESSAGING_ADMIN))
        .isFalse();
  }

  @Test
  void listedServiceIsServedEvenWhenTokenIsRequired() throws Exception {
    assertThat(run("Bearer " + tokens.generateToken("svc:elcm_service"), true)).isNotNull();
  }

  @Test
  void unlistedServiceStaysUnverified() throws Exception {
    assertThat(run("Bearer " + tokens.generateToken("svc:ecws_service"), false)).isNull();
  }

  @Test
  void aTokenThatIsNotValidStaysUnverified() throws Exception {
    assertThat(run("Bearer not-a-real-token", false)).isNull();
  }

  @Test
  void noAuthorizationHeaderStaysUnverified() throws Exception {
    assertThat(run(null, false)).isNull();
  }

  @Test
  void serviceRoleMayOnlyViewFiles() {
    assertThat(
            CommonRolePermissions.isGranted(
                java.util.List.of(CommonRolePermissions.SERVICE_ROLE), CommonPermission.FILE_UPLOAD))
        .isFalse();
  }
}

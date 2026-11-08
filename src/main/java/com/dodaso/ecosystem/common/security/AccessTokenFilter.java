package com.dodaso.ecosystem.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dodaso.ecosystem.baseline.common.security.JwtTokenUtil;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the SSO access token that elcm-ui forwards in {@code X-Access-Token}: signature
 * (against the SSO public keys), issuer and expiry. A valid token becomes the caller identity:
 * the Security context holds it (so audit columns use the token subject), and the caller with
 * the token's {@code roles} claim is stored for {@link PermissionInterceptor}.
 *
 * <ul>
 *   <li>Token present but invalid or expired: always 401.
 *   <li>Token absent: 401 when {@code required}; otherwise the request passes and identity falls
 *       back to the X-User-Context header (rollout mode, logged).
 * </ul>
 *
 * <p>ADDED 2026-11-06. Background work has no user and so no SSO token (the ELCM extraction worker
 * downloading a file). Such a call carries the platform bearer token in {@code Authorization},
 * signed with the shared platform secret, whose subject is {@code svc:<application name>}. When
 * that subject is listed in {@code dodaso.authz.service-callers}, the caller is the service, with
 * the role {@link CommonRolePermissions#SERVICE_ROLE}. A user token, when present, always wins.
 *
 * <p>Applies to /api/**, /userMessagingIdentifierController/** and /systemMessagingConfigController/**
 * only. Not a Spring bean: it is
 * added to the security filter chain in
 * {@link com.dodaso.ecosystem.common.config.CommonServiceSecurityConfig}, after the context is set up.
 */
public class AccessTokenFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Access-Token";
  private static final Logger log = LoggerFactory.getLogger(AccessTokenFilter.class);

  private final JwtDecoder decoder;
  private final boolean required;
  private final JwtTokenUtil platformTokens;
  private final Set<String> serviceCallers;

  public AccessTokenFilter(JwtDecoder decoder, boolean required) {
    this(decoder, required, null, Set.of());
  }

  public AccessTokenFilter(
      JwtDecoder decoder, boolean required, JwtTokenUtil platformTokens, Set<String> serviceCallers) {
    this.decoder = decoder;
    this.required = required;
    this.platformTokens = platformTokens;
    this.serviceCallers = serviceCallers == null ? Set.of() : Set.copyOf(serviceCallers);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    // return path == null || !path.startsWith("/api/");
    // Messaging identifiers are per user, so their calls need the caller too
    // REVISED 2026-10-27: the messaging channel settings need the caller too (admin check)
    return path == null || !(path.startsWith("/api/")
        || path.startsWith("/userMessagingIdentifierController/")
        || path.startsWith("/systemMessagingConfigController/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String raw = request.getHeader(HEADER);
    if (raw == null || raw.isBlank()) {
      String service = verifiedServiceCaller(request);
      if (service != null) {
        log.debug("Service caller {} on {} {}", service, request.getMethod(),
            request.getRequestURI());
        request.setAttribute(
            VerifiedCaller.ATTRIBUTE,
            new VerifiedCaller(service, List.of(CommonRolePermissions.SERVICE_ROLE)));
        chain.doFilter(request, response);
        return;
      }
      if (required) {
        reject(response, "Access token required");
        return;
      }
      log.debug("No access token on {} {}, falling back to X-User-Context", request.getMethod(),
          request.getRequestURI());
      chain.doFilter(request, response);
      return;
    }

    Jwt jwt;
    try {
      jwt = decoder.decode(raw.trim());
    } catch (JwtException e) {
      log.warn("Rejected access token on {} {}: {}", request.getMethod(), request.getRequestURI(),
          e.getMessage());
      reject(response, "Invalid access token");
      return;
    }
    String login = jwt.getSubject();
    if (login == null || login.isBlank()) {
      reject(response, "Access token has no subject");
      return;
    }
    List<String> roles = jwt.getClaimAsStringList("roles");
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new JwtAuthenticationToken(jwt, List.of(), login));
    SecurityContextHolder.setContext(context);
    request.setAttribute(
        VerifiedCaller.ATTRIBUTE, new VerifiedCaller(login, roles == null ? List.of() : roles));
    chain.doFilter(request, response);
  }

  /**
   * The subject of a valid platform bearer token that is on the service caller list, else null.
   * A missing, invalid, expired or unlisted token is not an error here: the request simply stays
   * unverified, as before.
   */
  private String verifiedServiceCaller(HttpServletRequest request) {
    if (platformTokens == null || serviceCallers.isEmpty()) {
      return null;
    }
    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    try {
      String subject = platformTokens.getUsernameFromToken(header.substring(7).trim());
      return subject != null && serviceCallers.contains(subject) ? subject : null;
    } catch (RuntimeException e) {
      log.debug("Platform token not accepted on {} {}: {}", request.getMethod(),
          request.getRequestURI(), e.getMessage());
      return null;
    }
  }

  private static void reject(HttpServletResponse response, String message) throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, message);
  }
}

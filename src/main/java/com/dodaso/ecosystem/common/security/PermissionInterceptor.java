package com.dodaso.ecosystem.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RequiresPermission} on controller methods.
 *
 * <p>The caller comes from the validated SSO access token (see {@link AccessTokenFilter}), whose
 * {@code roles} claim is checked. A request without a validated token has no verified identity,
 * so no permission can be granted to it.
 *
 * <p>Rollout switch {@code dodaso.authz.enforce} (env {@code DODASO_AUTHZ_ENFORCE}), default
 * false: a denied call is only logged as a warning. Set to true to return 403.
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(PermissionInterceptor.class);

  @Value("${dodaso.authz.enforce:false}")
  private boolean enforce;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
    if (required == null) {
      required = method.getBeanType().getAnnotation(RequiresPermission.class);
    }
    if (required == null) {
      return true;
    }

    String caller = "unverified";
    List<String> roles = List.of();
    Object verified = request.getAttribute(VerifiedCaller.ATTRIBUTE);
    if (verified instanceof VerifiedCaller vc) {
      caller = vc.loginId();
      roles = vc.roles();
    }
    if (CommonRolePermissions.isGranted(roles, required.value())) {
      return true;
    }

    String detail =
        "caller=" + caller + " roles=" + roles + " permission=" + required.value().getCode()
            + " " + request.getMethod() + " " + request.getRequestURI();
    if (enforce) {
      log.warn("Permission denied: {}", detail);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permission denied");
    }
    log.warn("Permission would be denied (enforcement off): {}", detail);
    return true;
  }
}

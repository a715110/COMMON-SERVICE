package com.dodaso.ecosystem.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The verified caller of the current request, as stored by {@link AccessTokenFilter}. For rules
 * that depend on who is calling, such as "users change only their own rows". Static, not a bean.
 */
public final class CallerLogin {

  private static final Logger log = LoggerFactory.getLogger(CallerLogin.class);

  private CallerLogin() {
  }

  /** The verified caller of the current request, if it sent a valid token. */
  public static Optional<VerifiedCaller> verified() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servlet) {
      return verified(servlet.getRequest());
    }
    return Optional.empty();
  }

  public static Optional<VerifiedCaller> verified(HttpServletRequest request) {
    Object value = request.getAttribute(VerifiedCaller.ATTRIBUTE);
    if (value instanceof VerifiedCaller caller && caller.loginId() != null
        && !caller.loginId().isBlank()) {
      return Optional.of(caller);
    }
    return Optional.empty();
  }

  /**
   * The login a request should act for, given the login its body names. With a valid token the
   * token's login wins; a different claimed login is replaced and logged. Without a token
   * (rollout mode) the claimed login is kept as before.
   */
  public static String actingFor(String claimedLogin, String where) {
    Optional<VerifiedCaller> caller = verified();
    if (caller.isEmpty()) {
      return claimedLogin;
    }
    String login = caller.get().loginId();
    if (claimedLogin != null && !claimedLogin.isBlank() && !claimedLogin.equalsIgnoreCase(login)) {
      log.warn("{}: request named '{}' but the token is for '{}'; using the token's login", where,
          claimedLogin, login);
    }
    return login;
  }

  /** True when there is no verified caller, or the caller is the given login. */
  public static boolean mayActFor(String ownerLogin) {
    Optional<VerifiedCaller> caller = verified();
    return caller.isEmpty() || caller.get().loginId().equalsIgnoreCase(ownerLogin);
  }
}

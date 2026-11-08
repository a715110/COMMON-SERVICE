package com.dodaso.ecosystem.common.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import com.dodaso.ecosystem.baseline.common.config.AbstractBaseSecurityConfig;
import com.dodaso.ecosystem.baseline.common.security.JwtTokenUtil;
import com.dodaso.ecosystem.common.security.AccessTokenFilter;

@Configuration
@EnableWebSecurity
public class CommonServiceSecurityConfig extends AbstractBaseSecurityConfig {

    @Value("${spring.profiles.active}")
    private String activeProfile;

    @Autowired
    private JwtDecoder ssoJwtDecoder;

    /**
     * Rollout switch (env DODASO_AUTHZ_TOKEN_REQUIRED). false: a request without an access token
     * is still served and logged. true: it gets 401. A token that is present but invalid is
     * rejected either way. Leave false while another product (ECWS) still calls this service
     * without forwarding the SSO token.
     */
    @Value("${dodaso.authz.token.required:false}")
    private boolean tokenRequired;

    /**
     * ADDED 2026-11-06. Platform token subjects (svc:application name) allowed to call without a
     * user, for background work such as the ELCM extraction worker downloading a file. They get
     * the Platform Service role (file view only). Empty turns this off.
     */
    @Value("${dodaso.authz.service-callers:svc:elcm_service}")
    private String serviceCallers;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Override
    protected void configureAdditionalSecurity(HttpSecurity http) throws Exception {
        http.addFilterBefore(new AccessTokenFilter(ssoJwtDecoder, tokenRequired, jwtTokenUtil, callerSet()), AuthorizationFilter.class);
    }

    @Override
    protected void configureAuthorizationRules(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.requestMatchers("/**").permitAll());
    }

    private Set<String> callerSet() {
        return Arrays.stream(serviceCallers.split(","))
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .collect(Collectors.toSet());
    }
}

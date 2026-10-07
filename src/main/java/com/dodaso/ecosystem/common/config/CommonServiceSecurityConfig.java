package com.dodaso.ecosystem.common.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import com.dodaso.ecosystem.baseline.common.config.AbstractBaseSecurityConfig;
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

    @Override
    protected void configureAdditionalSecurity(HttpSecurity http) throws Exception {
        http.addFilterBefore(new AccessTokenFilter(ssoJwtDecoder, tokenRequired), AuthorizationFilter.class);
    }

    @Override
    protected void configureAuthorizationRules(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.requestMatchers("/**").permitAll());
    }
}

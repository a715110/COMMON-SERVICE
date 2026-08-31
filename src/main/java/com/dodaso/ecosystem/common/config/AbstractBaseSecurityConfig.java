package com.dodaso.ecosystem.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

public abstract class AbstractBaseSecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Enforce baseline security defaults for all REST services
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // Hook for module-specific HTTP rules
        configureAuthorizationRules(http);

        // Hook for module-specific custom filters or authentication providers
        configureAdditionalSecurity(http);

        return http.build();
    }

    /**
     * Abstract hook: Each subclass MUST define its own endpoint permissions.
     */
    protected abstract void configureAuthorizationRules(HttpSecurity http) throws Exception;

    /**
     * Optional hook: Subclasses CAN override this to add custom filters or OAuth2 settings.
     */
    protected void configureAdditionalSecurity(HttpSecurity http) throws Exception {
        // Default: Do nothing extra
    }
}
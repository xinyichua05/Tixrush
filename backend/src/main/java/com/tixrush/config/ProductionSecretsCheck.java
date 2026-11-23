package com.tixrush.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start ONLINE with the development passwords from application.yml.
 *
 * "Online" = the host chose our port (hosts like Render set the PORT env var; on a laptop or in
 * docker compose it isn't set). There, the JWT secret and the admin password must be real secrets:
 * with the defaults from GitHub, anyone could sign their own admin token or log in as admin.
 */
@Component
public class ProductionSecretsCheck {

    static final String DEV_JWT_SECRET = "dev-only-secret-change-me-0123456789abcdef";
    static final String DEV_ADMIN_PASSWORD = "admin12345";

    public ProductionSecretsCheck(@Value("${PORT:}") String hostPort, JwtProperties jwt, AdminProperties admin) {
        if (hostPort.isBlank()) {
            return; // laptop / docker compose: dev defaults are fine
        }
        List<String> problems = new ArrayList<>();
        if (DEV_JWT_SECRET.equals(jwt.secret())) {
            problems.add("JWT_SECRET is still the development value");
        }
        if (DEV_ADMIN_PASSWORD.equals(admin.password())) {
            problems.add("ADMIN_PASSWORD is still the development value");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start on a server with development secrets: "
                    + String.join("; ", problems) + ". Set them as environment variables on the host.");
        }
    }
}

package com.tixrush.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Online (PORT set by the host) the app must not start with the development secrets. */
class ProductionSecretsCheckTest {

    private static final JwtProperties DEV_JWT = new JwtProperties(ProductionSecretsCheck.DEV_JWT_SECRET, Duration.ofHours(1));
    private static final JwtProperties REAL_JWT = new JwtProperties("k3J9x-a-long-random-production-secret-9Qz7", Duration.ofHours(1));
    private static final AdminProperties DEV_ADMIN = new AdminProperties("admin@tixrush.local", ProductionSecretsCheck.DEV_ADMIN_PASSWORD);
    private static final AdminProperties REAL_ADMIN = new AdminProperties("owner@example.com", "a-real-password-7731");

    @Test
    void onALaptop_devDefaultsAreFine() {
        assertThatCode(() -> new ProductionSecretsCheck("", DEV_JWT, DEV_ADMIN)).doesNotThrowAnyException();
    }

    @Test
    void online_devDefaults_stopTheApp() {
        assertThatThrownBy(() -> new ProductionSecretsCheck("10000", DEV_JWT, REAL_ADMIN))
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new ProductionSecretsCheck("10000", REAL_JWT, DEV_ADMIN))
                .hasMessageContaining("ADMIN_PASSWORD");
    }

    @Test
    void online_realSecrets_start() {
        assertThatCode(() -> new ProductionSecretsCheck("10000", REAL_JWT, REAL_ADMIN)).doesNotThrowAnyException();
    }
}

package com.tixrush.config;

import com.tixrush.user.Role;
import com.tixrush.user.User;
import com.tixrush.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Runs once after the app starts. Registration only creates USERs, so this is how the
 * first ADMIN gets into the database. Skipped if that email already exists.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties admin;

    public AdminSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder, AdminProperties admin) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.admin = admin;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (admin.email() == null || admin.password() == null) {
            return;
        }
        String email = admin.email().trim().toLowerCase();
        if (!userRepository.existsByEmail(email)) {
            userRepository.save(new User(email, passwordEncoder.encode(admin.password()), Role.ADMIN));
            log.info("Created admin account {}", email);
        }
    }
}

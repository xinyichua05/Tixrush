package com.tixrush.order;

import com.tixrush.user.Role;
import com.tixrush.user.User;
import com.tixrush.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 50 shared "pretend buyer" accounts, used by the visualizer rush and the Live Rush bot fans.
 * Orders need a real user id (foreign key), so they exist as rows in users.
 * They never log in, so they all get one random password hashed once (BCrypt is slow on purpose).
 */
@Component
public class SimBuyers {

    static final int COUNT = 50;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private volatile List<Long> ids;

    public SimBuyers(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Creates the accounts the first time, then returns the cached ids. */
    public synchronized List<Long> ids() {
        if (ids != null) {
            return ids;
        }
        String sharedHash = null;
        List<Long> found = new ArrayList<>(COUNT);
        for (int i = 1; i <= COUNT; i++) {
            String email = "sim-buyer-" + i + "@tixrush.local";
            User user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                if (sharedHash == null) {
                    sharedHash = passwordEncoder.encode(UUID.randomUUID().toString());
                }
                user = userRepository.save(new User(email, sharedHash, Role.USER));
            }
            found.add(user.getId());
        }
        ids = List.copyOf(found);
        return ids;
    }
}

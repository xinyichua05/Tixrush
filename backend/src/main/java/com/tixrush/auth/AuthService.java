package com.tixrush.auth;

import com.tixrush.common.ConflictException;
import com.tixrush.common.UnauthorizedException;
import com.tixrush.user.Role;
import com.tixrush.user.User;
import com.tixrush.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Register and login logic. */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }
        // Store only the BCrypt hash. Everyone who registers is a USER; admins come from AdminSeeder.
        User user = userRepository.save(new User(email, passwordEncoder.encode(request.password()), Role.USER));
        return jwtService.issueToken(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Same message for "no such email" and "wrong password", so attackers can't
        // use the login form to discover which emails have accounts.
        User user = userRepository.findByEmail(normalize(request.email()))
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        return jwtService.issueToken(user);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase();
    }
}

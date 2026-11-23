package com.tixrush.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * "Derived queries": Spring Data reads the method NAME and writes the SQL.
 *   findByEmail   -> select ... from users where email = ?
 *   existsByEmail -> select count/exists ... where email = ?
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}

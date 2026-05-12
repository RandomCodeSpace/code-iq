package com.example.checkout;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link User}. EntityLinker matches
 * "UserRepository" → "User" by stripping the "Repository" suffix.
 */
public interface UserRepository extends JpaRepository<User, Long> {
}

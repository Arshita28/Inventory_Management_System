package com.inventory.security;

import java.util.Objects;
import java.util.UUID;

/** Immutable authenticated identity, also usable as an explicit actor in domain tests. */
public record User(UUID id, String username, Role role, boolean active) {
    public User {
        Objects.requireNonNull(id);
        Objects.requireNonNull(role);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        username = username.strip();
    }
}

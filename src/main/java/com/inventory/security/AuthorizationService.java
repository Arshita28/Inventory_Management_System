package com.inventory.security;

import com.inventory.exception.AccessDeniedException;

public final class AuthorizationService {
    public void require(User actor, Permission permission) {
        if (actor == null || !actor.active() || !actor.role().allows(permission)) {
            throw new AccessDeniedException("Permission required: " + permission);
        }
    }
}

package com.inventory.security;

import java.util.Set;

public enum Role {
    ADMIN(Set.of(Permission.values())),
    MANAGER(Set.of(Permission.VIEW_INVENTORY, Permission.MANAGE_PRODUCTS,
            Permission.RECEIVE_STOCK, Permission.SELL_STOCK, Permission.VIEW_HISTORY,
            Permission.VIEW_ANALYTICS, Permission.MANAGE_PURCHASES, Permission.APPROVE_PURCHASES)),
    STAFF(Set.of(Permission.VIEW_INVENTORY, Permission.SELL_STOCK));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public String label() { return switch (this) { case ADMIN -> "Admin"; case MANAGER -> "Manager"; case STAFF -> "Staff"; }; }

    public boolean allows(Permission permission) {
        return permissions.contains(permission);
    }
}

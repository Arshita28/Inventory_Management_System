package com.inventory.security.auth;

import com.inventory.security.Role;
import java.util.List;

/** Public, fictional credentials for the explicitly enabled local demonstration. */
public final class DemoPersonas {
    public record Persona(String name, String title, String initials, Role role, String username, String password) { }
    public static final List<Persona> ALL = List.of(
            new Persona("Maya Shah", "Business owner", "MS", Role.ADMIN, "demo.admin.maya", "VoltEdge-Demo-Maya-Admin!"),
            new Persona("Arjun Mehta", "Account administrator", "AM", Role.ADMIN, "demo.admin.arjun", "VoltEdge-Demo-Arjun-Admin!"),
            new Persona("Neha Rao", "Operations manager", "NR", Role.MANAGER, "demo.manager.neha", "VoltEdge-Demo-Neha-Manager!"),
            new Persona("Kabir Sethi", "Purchasing manager", "KS", Role.MANAGER, "demo.manager.kabir", "VoltEdge-Demo-Kabir-Manager!"),
            new Persona("Riya Patel", "Sales associate", "RP", Role.STAFF, "demo.staff.riya", "VoltEdge-Demo-Riya-Staff!"),
            new Persona("Aman Verma", "Counter associate", "AV", Role.STAFF, "demo.staff.aman", "VoltEdge-Demo-Aman-Staff!")
    );
    private DemoPersonas() { }
}

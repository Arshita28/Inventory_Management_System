package com.inventory.security.auth;

import com.inventory.exception.AccessDeniedException;
import com.inventory.security.*;
import java.time.*;
import java.util.*;

/** Authentication and account administration; independent of HTTP and HTML. */
public final class AccessService {
    public static final String LOGIN_ERROR = "Unable to log in. Check your details and selected role, or try again later.";
    private record Attempts(int count, Instant until) { }
    private final JdbcAccountRepository accounts;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final Map<String, Attempts> failures = new HashMap<>();
    private final ArrayDeque<Instant> recentAttempts = new ArrayDeque<>();
    private final String dummyHash;
    private final SessionManager sessions;

    public AccessService(JdbcAccountRepository accounts, Clock clock) {
        this.accounts = accounts; this.clock = clock; hasher = new PasswordHasher();
        dummyHash = hasher.hash(UUID.randomUUID().toString().toCharArray());
        sessions = new SessionManager(clock);
    }
    public SessionManager sessions() { return sessions; }
    public boolean needsSetup() { return accounts.list().isEmpty(); }
    public Optional<User> find(UUID id) { return accounts.find(id); }
    public List<User> users(User actor) { authorize(actor); return accounts.list(); }

    /** Explicit startup provisioning; existing accounts and passwords are never overwritten. */
    public synchronized void installDemoPersonas() {
        for (DemoPersonas.Persona persona : DemoPersonas.ALL) {
            if (accounts.find(persona.username()).isPresent()) continue;
            char[] password = persona.password().toCharArray();
            try {
                if (needsSetup()) setup(persona.username(), password);
                else {
                    User admin = accounts.list().stream().filter(u -> u.active() && u.role() == Role.ADMIN).findFirst()
                            .orElseThrow(() -> new IllegalStateException("An active Admin is required to add demo personas."));
                    create(admin, persona.username(), password, persona.role());
                }
            } catch (IllegalArgumentException exception) {
                // Another initializer may have created the same fixture concurrently.
                if (accounts.find(persona.username()).isEmpty()) throw exception;
            } finally { Arrays.fill(password, '\0'); }
        }
    }

    /** Do not offer a persona if an Admin disabled it or changed its role/password. */
    public List<DemoPersonas.Persona> demoPersonas() {
        return DemoPersonas.ALL.stream().filter(persona -> {
            var credential = accounts.find(persona.username());
            if (credential.isEmpty() || !credential.get().user().active() || credential.get().user().role() != persona.role()) return false;
            char[] password = persona.password().toCharArray();
            try { return hasher.matches(password, credential.get().hash()); }
            finally { Arrays.fill(password, '\0'); }
        }).toList();
    }

    public User setup(String username, char[] password) {
        User user = new User(UUID.randomUUID(), username(username), Role.ADMIN, true);
        accounts.create(null, user, hasher.hash(password), true, clock.instant().toString());
        return user;
    }
    public User create(User actor, String username, char[] password, Role role) {
        authorize(actor);
        User user = new User(UUID.randomUUID(), username(username), role, true);
        accounts.create(actor, user, hasher.hash(password), false, clock.instant().toString());
        return user;
    }
    public void update(User actor, UUID id, Role role, boolean active, char[] password) {
        authorize(actor);
        String hash = password.length == 0 ? null : hasher.hash(password);
        accounts.update(actor, id, role, active, hash, clock.instant().toString());
        sessions.invalidateUser(id);
    }
    private void authorize(User actor) {
        User current = actor == null ? null : accounts.find(actor.id()).orElse(null);
        new AuthorizationService().require(current, Permission.MANAGE_USERS);
    }

    public synchronized User login(String username, char[] password, Role requestedRole) {
        String key = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        recentAttempts.removeIf(time -> time.isBefore(now.minusSeconds(60)));
        if (recentAttempts.size() >= 30) throw new AccessDeniedException(LOGIN_ERROR);
        recentAttempts.add(now);
        failures.values().removeIf(attempt -> !now.isBefore(attempt.until()));
        Attempts failure = failures.get(key);
        if (failure != null && failure.count() >= 5) throw new AccessDeniedException(LOGIN_ERROR);
        var stored = accounts.find(key);
        boolean matches = hasher.matches(password, stored.map(JdbcAccountRepository.Credential::hash).orElse(dummyHash));
        if (!matches || stored.isEmpty() || !stored.get().user().active() || stored.get().user().role() != requestedRole) {
            if (failures.size() >= 1000) failures.clear();
            failures.put(key, new Attempts(failure == null ? 1 : failure.count() + 1, failure == null ? now.plusSeconds(600) : failure.until()));
            throw new AccessDeniedException(LOGIN_ERROR);
        }
        failures.remove(key);
        return stored.get().user();
    }
    private String username(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{3,40}")) throw new IllegalArgumentException("Use 3–40 letters, numbers, dots, underscores or hyphens for the username.");
        return value.toLowerCase(Locale.ROOT);
    }
}

package com.inventory.security.auth;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** Opaque, server-side sessions. Passwords and roles never travel in the cookie. */
public final class SessionManager {
    public record Session(String token, String csrf, UUID userId, Instant created, Instant lastSeen) { }
    private final Map<String, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    public SessionManager(Clock clock) { this.clock = clock; }
    private String randomToken() { byte[] value = new byte[32]; random.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }

    public synchronized Session create(UUID userId) {
        sessions.values().removeIf(this::expired);
        if (sessions.size() >= 1000) sessions.remove(sessions.values().stream().min(Comparator.comparing(Session::lastSeen)).orElseThrow().token());
        var session = new Session(randomToken(), randomToken(), userId, clock.instant(), clock.instant());
        sessions.put(session.token(), session);
        return session;
    }
    public synchronized Optional<Session> find(String token) {
        Session value = sessions.get(token);
        if (value == null) return Optional.empty();
        if (expired(value)) { sessions.remove(token); return Optional.empty(); }
        var refreshed = new Session(value.token(), value.csrf(), value.userId(), value.created(), clock.instant());
        sessions.put(token, refreshed);
        return Optional.of(refreshed);
    }
    private boolean expired(Session s) {
        return !clock.instant().isBefore(s.lastSeen().plus(Duration.ofMinutes(s.userId() == null ? 10 : 30)))
                || !clock.instant().isBefore(s.created().plus(Duration.ofHours(8)));
    }
    public synchronized void invalidate(String token) { sessions.remove(token); }
    public synchronized void invalidateUser(UUID id) { sessions.values().removeIf(s -> id.equals(s.userId())); }
}

package com.inventory.security.auth;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {
    private static final class Time extends Clock {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void anonymousAndAuthenticatedSessionsExpireAndTokensAreIndependent() {
        var time = new Time(); var sessions = new SessionManager(time);
        var anon = sessions.create(null); var user = sessions.create(UUID.randomUUID());
        assertEquals(43, user.token().length());
        assertNotEquals(user.token(), user.csrf());
        assertNotEquals(anon.token(), user.token());
        time.advance(Duration.ofMinutes(10));
        assertTrue(sessions.find(anon.token()).isEmpty());
        assertTrue(sessions.find(user.token()).isPresent());
        time.advance(Duration.ofMinutes(30));
        assertTrue(sessions.find(user.token()).isEmpty());
    }
    @Test void activityCannotExtendTheEightHourMaximum() {
        var time = new Time(); var sessions = new SessionManager(time);
        var s = sessions.create(UUID.randomUUID());
        for (int i = 0; i < 47; i++) { time.advance(Duration.ofMinutes(10)); assertTrue(sessions.find(s.token()).isPresent()); }
        time.advance(Duration.ofMinutes(10));
        assertTrue(sessions.find(s.token()).isEmpty());
    }
}

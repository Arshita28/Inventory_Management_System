package com.inventory.security.auth;

import com.inventory.exception.AccessDeniedException;
import com.inventory.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AccessServiceTest {
    private static final String PASSWORD = "A unique test passphrase 42!";
    private AccessService service(String url) { return new AccessService(new JdbcAccountRepository(url), Clock.systemUTC()); }
    private String database() { return "jdbc:h2:mem:auth-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"; }

    @Test void credentialsPersistAsSaltedHashesAndRoleSelectionCannotElevate(@TempDir Path dir) throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("accounts");
        var access = service(url);
        var admin = access.setup("Owner", PASSWORD.toCharArray());
        access.create(admin, "seller", PASSWORD.toCharArray(), Role.STAFF);
        assertEquals(Role.STAFF, service(url).login("SELLER", PASSWORD.toCharArray(), Role.STAFF).role());
        assertThrows(AccessDeniedException.class, () -> access.login("seller", PASSWORD.toCharArray(), Role.ADMIN));
        var repository = new JdbcAccountRepository(url);
        String a = repository.find("owner").orElseThrow().hash();
        String b = repository.find("seller").orElseThrow().hash();
        assertNotEquals(a, b);
        assertFalse(a.contains(PASSWORD));
        assertTrue(a.startsWith("pbkdf2-sha256$600000$"));
    }
    @Test void setupIsOneTimeAndLastAdminIsProtected() {
        var access = service(database());
        var admin = access.setup("owner", PASSWORD.toCharArray());
        assertThrows(IllegalArgumentException.class, () -> access.setup("other", PASSWORD.toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> access.update(admin, admin.id(), Role.ADMIN, false, new char[0]));
        assertThrows(IllegalArgumentException.class, () -> access.update(admin, admin.id(), Role.STAFF, true, new char[0]));
        assertTrue(access.find(admin.id()).orElseThrow().active());
    }
    @Test void forgedActorAndManagersCannotAdministerAccounts() {
        var access = service(database());
        var admin = access.setup("owner", PASSWORD.toCharArray());
        var manager = access.create(admin, "manager", PASSWORD.toCharArray(), Role.MANAGER);
        var forged = new User(manager.id(), manager.username(), Role.ADMIN, true);
        assertThrows(AccessDeniedException.class, () -> access.create(forged, "intruder", PASSWORD.toCharArray(), Role.ADMIN));
        assertThrows(AccessDeniedException.class, () -> access.users(manager));
    }
    @Test void disablingOrResettingAccountInvalidatesSessionsAndPassword() {
        var access = service(database());
        var admin = access.setup("owner", PASSWORD.toCharArray());
        var staff = access.create(admin, "staff", PASSWORD.toCharArray(), Role.STAFF);
        var session = access.sessions().create(staff.id());
        access.update(admin, staff.id(), Role.STAFF, false, new char[0]);
        assertTrue(access.sessions().find(session.token()).isEmpty());
        assertThrows(AccessDeniedException.class, () -> access.login("staff", PASSWORD.toCharArray(), Role.STAFF));
        access.update(admin, staff.id(), Role.MANAGER, true, "Another long test password!".toCharArray());
        assertThrows(AccessDeniedException.class, () -> access.login("staff", PASSWORD.toCharArray(), Role.MANAGER));
        assertEquals(Role.MANAGER, access.login("staff", "Another long test password!".toCharArray(), Role.MANAGER).role());
    }
    @Test void failuresAreGenericAndRepeatedAttemptsAreLimited() {
        var access = service(database());
        access.setup("owner", PASSWORD.toCharArray());
        for (int i = 0; i < 5; i++) {
            assertEquals(AccessService.LOGIN_ERROR, assertThrows(AccessDeniedException.class,
                    () -> access.login("owner", "wrong".toCharArray(), Role.ADMIN)).getMessage());
        }
        assertThrows(AccessDeniedException.class, () -> access.login("owner", PASSWORD.toCharArray(), Role.ADMIN));
        assertEquals(AccessService.LOGIN_ERROR, assertThrows(AccessDeniedException.class,
                () -> access.login("unknown", PASSWORD.toCharArray(), Role.ADMIN)).getMessage());
    }
    @Test void setupSerializesAcrossRepositoryInstances() throws Exception {
        String url = database();
        var first = service(url); var second = service(url);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> { try { first.setup("first", PASSWORD.toCharArray()); return true; } catch (IllegalArgumentException e) { return false; } });
            var two = executor.submit(() -> { try { second.setup("second", PASSWORD.toCharArray()); return true; } catch (IllegalArgumentException e) { return false; } });
            assertNotEquals(one.get(), two.get());
        }
        assertEquals(1, new JdbcAccountRepository(url).list().size());
    }
    @Test void demoPersonasCoverAllRolesAndRepeatedProvisioningPreservesIdentities() {
        var access = service(database());
        access.installDemoPersonas();
        assertFalse(access.needsSetup());
        assertEquals(6, access.demoPersonas().size());
        Map<String, UUID> identities = new HashMap<>();
        for (var persona : DemoPersonas.ALL) {
            var user = access.login(persona.username(), persona.password().toCharArray(), persona.role());
            assertEquals(persona.role(), user.role());
            identities.put(persona.username(), user.id());
        }
        access.installDemoPersonas();
        for (var persona : DemoPersonas.ALL) assertEquals(identities.get(persona.username()),
                access.login(persona.username(), persona.password().toCharArray(), persona.role()).id());
        for (Role role : Role.values()) assertEquals(2, access.demoPersonas().stream().filter(p -> p.role() == role).count());
    }
    @Test void demoSetupPreservesExistingAccountsAndDoesNotResetChangedPersonas() {
        var access = service(database());
        var owner = access.setup("existing.owner", PASSWORD.toCharArray());
        access.installDemoPersonas();
        assertEquals(owner.id(), access.login("existing.owner", PASSWORD.toCharArray(), Role.ADMIN).id());
        var persona = DemoPersonas.ALL.getLast();
        var staff = access.login(persona.username(), persona.password().toCharArray(), persona.role());
        access.update(owner, staff.id(), Role.STAFF, true, PASSWORD.toCharArray());
        access.installDemoPersonas();
        assertEquals(staff.id(), access.login(persona.username(), PASSWORD.toCharArray(), Role.STAFF).id());
        assertFalse(access.demoPersonas().contains(persona));
    }
    @Test void passwordAndUsernameValidationDoesNotCreateBadAccounts() {
        var access = service(database());
        assertThrows(IllegalArgumentException.class, () -> access.setup("bad user", PASSWORD.toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> access.setup("owner", "short".toCharArray()));
        assertTrue(access.needsSetup());
    }
}

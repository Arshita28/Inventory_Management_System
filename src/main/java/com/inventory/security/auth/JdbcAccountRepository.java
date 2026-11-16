package com.inventory.security.auth;

import com.inventory.exception.PersistenceException;
import com.inventory.security.Role;
import com.inventory.security.User;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Account mutations share a database lock, including first-admin and last-admin checks. */
public final class JdbcAccountRepository {
    public record Credential(User user, String hash) { }
    private final String url;

    public JdbcAccountRepository(String url) {
        this.url = Objects.requireNonNull(url);
        try (var input = getClass().getResourceAsStream("/database/V004__accounts.sql"); var c = connect()) {
            if (input == null) throw new IllegalStateException("Account schema is missing");
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) try (var statement = c.createStatement()) { statement.execute(sql); }
            }
        } catch (java.io.IOException | SQLException exception) { throw failure(exception); }
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection(url, "sa", ""); }
    private PersistenceException failure(Exception cause) { return new PersistenceException("Unable to access account storage", cause); }

    public Optional<Credential> find(String username) {
        try (var c = connect(); var q = c.prepareStatement("SELECT * FROM app_users WHERE username=?")) {
            q.setString(1, username);
            try (var rs = q.executeQuery()) { return rs.next() ? Optional.of(new Credential(user(rs), rs.getString("password_hash"))) : Optional.empty(); }
        } catch (SQLException exception) { throw failure(exception); }
    }

    public Optional<User> find(UUID id) { return list().stream().filter(u -> u.id().equals(id)).findFirst(); }

    public List<User> list() {
        try (var c = connect(); var q = c.prepareStatement("SELECT id,username,role,active FROM app_users ORDER BY username"); var rs = q.executeQuery()) {
            List<User> users = new ArrayList<>();
            while (rs.next()) users.add(user(rs));
            return List.copyOf(users);
        } catch (SQLException exception) { throw failure(exception); }
    }

    public void create(User actor, User user, String hash, boolean bootstrap, String now) {
        transaction(c -> {
            if (bootstrap) {
                try (var q = c.createStatement(); var rs = q.executeQuery("SELECT COUNT(*) FROM app_users")) {
                    rs.next();
                    if (rs.getInt(1) != 0 || user.role() != Role.ADMIN) throw new IllegalArgumentException("Admin setup is already complete.");
                }
            } else requireAdmin(c, actor);
            try (var q = c.prepareStatement("INSERT INTO app_users(id,username,password_hash,role,active,created_at) VALUES(?,?,?,?,?,?)")) {
                q.setString(1, user.id().toString()); q.setString(2, user.username()); q.setString(3, hash);
                q.setString(4, user.role().name()); q.setBoolean(5, true); q.setString(6, now); q.executeUpdate();
            } catch (SQLIntegrityConstraintViolationException exception) { throw new IllegalArgumentException("That username is already in use."); }
            event(c, bootstrap ? user.id() : actor.id(), user.id(), bootstrap ? "ADMIN_SETUP" : "ACCOUNT_CREATED", now);
        });
    }

    public void update(User actor, UUID id, Role role, boolean active, String newHash, String now) {
        transaction(c -> {
            requireAdmin(c, actor);
            User target;
            try (var q = c.prepareStatement("SELECT * FROM app_users WHERE id=?")) {
                q.setString(1, id.toString());
                try (var rs = q.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("Account not found."); target = user(rs); }
            }
            if (target.active() && target.role() == Role.ADMIN && (!active || role != Role.ADMIN)) {
                try (var q = c.createStatement(); var rs = q.executeQuery("SELECT COUNT(*) FROM app_users WHERE role='ADMIN' AND active=TRUE")) {
                    rs.next(); if (rs.getInt(1) <= 1) throw new IllegalArgumentException("Keep at least one active Admin account.");
                }
            }
            try (var q = c.prepareStatement("UPDATE app_users SET role=?,active=?,password_hash=COALESCE(?,password_hash) WHERE id=?")) {
                q.setString(1, role.name()); q.setBoolean(2, active); q.setString(3, newHash); q.setString(4, id.toString()); q.executeUpdate();
            }
            event(c, actor.id(), id, newHash == null ? "ACCOUNT_UPDATED" : "ACCOUNT_AND_PASSWORD_UPDATED", now);
        });
    }

    private void requireAdmin(Connection c, User actor) throws SQLException {
        if (actor == null) throw new com.inventory.exception.AccessDeniedException("Admin access required");
        try (var q = c.prepareStatement("SELECT role,active FROM app_users WHERE id=?")) {
            q.setString(1, actor.id().toString());
            try (var rs = q.executeQuery()) {
                if (!rs.next() || !rs.getBoolean("active") || !rs.getString("role").equals("ADMIN")) throw new com.inventory.exception.AccessDeniedException("Admin access required");
            }
        }
    }

    private User user(ResultSet rs) throws SQLException { return new User(UUID.fromString(rs.getString("id")), rs.getString("username"), Role.valueOf(rs.getString("role")), rs.getBoolean("active")); }
    private void event(Connection c, UUID actor, UUID target, String action, String now) throws SQLException {
        try (var q = c.prepareStatement("INSERT INTO access_events(actor_id,target_id,action,occurred_at) VALUES(?,?,?,?)")) {
            q.setString(1, actor.toString()); q.setString(2, target.toString()); q.setString(3, action); q.setString(4, now); q.executeUpdate();
        }
    }
    private interface Work { void run(Connection c) throws SQLException; }
    private void transaction(Work work) {
        try (var c = connect()) {
            c.setAutoCommit(false);
            try {
                try (var q = c.createStatement(); var rs = q.executeQuery("SELECT id FROM access_write_lock WHERE id=1 FOR UPDATE")) { rs.next(); }
                work.run(c); c.commit();
            } catch (RuntimeException | SQLException exception) { c.rollback(); throw exception; }
        } catch (SQLException exception) { throw failure(exception); }
    }
}

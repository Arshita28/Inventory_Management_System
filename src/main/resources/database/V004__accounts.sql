CREATE TABLE IF NOT EXISTS access_write_lock (id INTEGER PRIMARY KEY CHECK (id = 1));
INSERT INTO access_write_lock (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM access_write_lock WHERE id = 1);
CREATE TABLE IF NOT EXISTS app_users (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(40) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'STAFF')),
    active BOOLEAN NOT NULL,
    created_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS access_events (
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id VARCHAR(36) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    action VARCHAR(80) NOT NULL,
    occurred_at VARCHAR(40) NOT NULL
);

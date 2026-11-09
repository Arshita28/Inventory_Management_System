CREATE TABLE IF NOT EXISTS schema_migrations (
    version INTEGER PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);
ALTER TABLE products ADD COLUMN IF NOT EXISTS details_type VARCHAR(40) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE products ADD COLUMN IF NOT EXISTS details_data CLOB NOT NULL DEFAULT '';
INSERT INTO schema_migrations (version, description)
SELECT 2, 'Electronics category details, preserve existing rows as legacy'
WHERE NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version = 2);

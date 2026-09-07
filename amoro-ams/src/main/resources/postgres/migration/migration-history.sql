-- Bookkeeping for the migrations AMS applies at startup. Created before anything else and never
-- itself recorded as a migration.
CREATE TABLE IF NOT EXISTS ams_schema_migration
(
    version     INT PRIMARY KEY,
    script_name VARCHAR(256) NOT NULL,
    checksum    VARCHAR(64),
    applied_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE ams_schema_migration IS 'Schema migrations already applied to this database';
COMMENT ON COLUMN ams_schema_migration.checksum IS 'SHA-256 of the script when it was applied';

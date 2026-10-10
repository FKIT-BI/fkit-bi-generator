CREATE SCHEMA equipment;
CREATE TABLE equipment.schema_release (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    release VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO equipment.schema_release (singleton, release) VALUES (TRUE, '002-authentication');

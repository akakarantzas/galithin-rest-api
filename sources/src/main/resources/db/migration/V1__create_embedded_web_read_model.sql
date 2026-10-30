CREATE TABLE IF NOT EXISTS web_players (
    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(16) NOT NULL,
    normalized_name VARCHAR(16) NOT NULL,
    level INT NOT NULL DEFAULT 0,
    experience BIGINT NOT NULL DEFAULT 0,
    eligible BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_web_players_normalized_name UNIQUE (normalized_name)
);

CREATE INDEX IF NOT EXISTS ix_web_players_level
    ON web_players (eligible, level DESC, experience DESC, uuid);

CREATE TABLE IF NOT EXISTS web_profiles (
    player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    profile_json CLOB NOT NULL,
    source_version_uuid VARCHAR(36) NULL,
    source_saved_at TIMESTAMP(6) NULL,
    projected_at TIMESTAMP(6) NOT NULL,
    stale BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_web_profiles_player FOREIGN KEY (player_uuid)
        REFERENCES web_players(uuid) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_web_profiles_freshness
    ON web_profiles (projected_at, stale);

CREATE TABLE IF NOT EXISTS web_guilds (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    tag VARCHAR(32) NULL,
    level INT NOT NULL DEFAULT 0,
    total_experience BIGINT NOT NULL DEFAULT 0,
    owner_uuid VARCHAR(36) NOT NULL,
    member_count INT NOT NULL DEFAULT 1,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    projected_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_web_guilds_level
    ON web_guilds (active, level DESC, total_experience DESC, id);

CREATE TABLE IF NOT EXISTS web_projection_state (
    source_name VARCHAR(64) NOT NULL PRIMARY KEY,
    checkpoint_timestamp TIMESTAMP(6) NULL,
    checkpoint_uuid VARCHAR(36) NULL,
    last_success_at TIMESTAMP(6) NULL,
    last_error CLOB NULL,
    stale BOOLEAN NOT NULL DEFAULT FALSE,
    catalog_version VARCHAR(128) NOT NULL DEFAULT 'unavailable'
);

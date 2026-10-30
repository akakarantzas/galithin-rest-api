ALTER TABLE web_guilds ADD COLUMN IF NOT EXISTS normalized_name VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE web_guilds ADD COLUMN IF NOT EXISTS normalized_tag VARCHAR(32) NULL;
ALTER TABLE web_guilds ADD COLUMN IF NOT EXISTS current_experience BIGINT NOT NULL DEFAULT 0;
ALTER TABLE web_guilds ADD COLUMN IF NOT EXISTS member_limit_bonus INT NOT NULL DEFAULT 0;
ALTER TABLE web_guilds ADD COLUMN IF NOT EXISTS claim_count INT NOT NULL DEFAULT 0;
ALTER TABLE web_guilds ADD COLUMN IF NOT EXISTS claim_limit_bonus INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS ix_web_guilds_name
    ON web_guilds (active, normalized_name);

CREATE INDEX IF NOT EXISTS ix_web_guilds_tag
    ON web_guilds (active, normalized_tag);

CREATE TABLE IF NOT EXISTS web_guild_members (
    guild_id VARCHAR(36) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    fallback_name VARCHAR(16) NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'member',
    total_contribution BIGINT NOT NULL DEFAULT 0,
    projected_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (guild_id, player_uuid),
    CONSTRAINT fk_web_guild_members_guild FOREIGN KEY (guild_id)
        REFERENCES web_guilds(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_web_guild_members_order
    ON web_guild_members (guild_id, role, total_contribution DESC, player_uuid);

CREATE TABLE url_mappings (
    id UUID PRIMARY KEY,
    short_code VARCHAR(32) NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_url_mappings_short_code UNIQUE (short_code),
    CONSTRAINT chk_url_mappings_short_code
        CHECK (short_code ~ '^[A-Za-z0-9_-]{4,32}$'),
    CONSTRAINT chk_url_mappings_expiration
        CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE INDEX idx_url_mappings_expires_at
    ON url_mappings (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE TABLE redirect_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    short_code VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_redirect_events_short_code
        CHECK (short_code ~ '^[A-Za-z0-9_-]{4,32}$')
);

-- No foreign key by design: analytics retention and best-effort writes are decoupled
-- from the lifecycle of the current URL mapping.
CREATE INDEX idx_redirect_events_short_code_occurred_at
    ON redirect_events (short_code, occurred_at DESC);


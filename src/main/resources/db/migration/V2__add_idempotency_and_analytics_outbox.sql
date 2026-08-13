ALTER TABLE url_mappings
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN request_fingerprint VARCHAR(64),
    ADD CONSTRAINT uq_url_mappings_idempotency_key UNIQUE (idempotency_key),
    ADD CONSTRAINT chk_url_mappings_idempotency_pair
        CHECK ((idempotency_key IS NULL) = (request_fingerprint IS NULL)),
    ADD CONSTRAINT chk_url_mappings_idempotency_key
        CHECK (idempotency_key IS NULL OR idempotency_key ~ '^[A-Za-z0-9._:-]{8,128}$'),
    ADD CONSTRAINT chk_url_mappings_request_fingerprint
        CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$');

CREATE TABLE redirect_analytics_outbox (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    short_code VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_redirect_analytics_outbox_short_code
        CHECK (short_code ~ '^[A-Za-z0-9_-]{4,32}$')
);

CREATE INDEX idx_redirect_analytics_outbox_short_code
    ON redirect_analytics_outbox (short_code);

CREATE INDEX idx_redirect_events_occurred_at
    ON redirect_events (occurred_at);

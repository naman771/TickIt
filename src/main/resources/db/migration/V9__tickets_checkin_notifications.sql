-- One independent admission credential per immutable paid checkout item/ordinal.
CREATE TABLE admission_ticket (
 id UUID PRIMARY KEY,
 item_id BIGINT NOT NULL REFERENCES checkout_item(id) ON DELETE CASCADE,
 ordinal INT NOT NULL CHECK (ordinal BETWEEN 1 AND 3),
 credential VARCHAR(64) NOT NULL UNIQUE,
 issued_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 checked_in_at TIMESTAMPTZ,
 checked_in_by BIGINT REFERENCES app_user(id),
 UNIQUE(item_id,ordinal),
 CHECK ((checked_in_at IS NULL) = (checked_in_by IS NULL))
);
CREATE INDEX idx_admission_ticket_item ON admission_ticket(item_id);
CREATE TABLE event_staff (
 event_id BIGINT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
 user_id BIGINT NOT NULL REFERENCES app_user(id),
 granted_by BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(event_id,user_id)
);
CREATE TABLE admission_scan (
 id BIGSERIAL PRIMARY KEY,
 event_id BIGINT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
 ticket_id UUID REFERENCES admission_ticket(id) ON DELETE CASCADE,
 actor_id BIGINT NOT NULL REFERENCES app_user(id),
 outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('ADMITTED','ALREADY_USED','INVALID','INACTIVE','TOO_EARLY','ENDED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_admission_scan_event ON admission_scan(event_id,created_at DESC);
CREATE TABLE notification_outbox (
 id UUID PRIMARY KEY,
 dedupe_key VARCHAR(180) NOT NULL UNIQUE,
 user_id BIGINT NOT NULL REFERENCES app_user(id),
 event_id BIGINT REFERENCES event(id) ON DELETE CASCADE,
 kind VARCHAR(32) NOT NULL,
 recipient VARCHAR(255) NOT NULL,
 subject VARCHAR(240) NOT NULL,
 body TEXT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RETRY','SENT','FAILED')),
 attempts INT NOT NULL DEFAULT 0,
 lease_token UUID,
 lease_until TIMESTAMPTZ,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 sent_at TIMESTAMPTZ,
 last_error VARCHAR(120)
);
CREATE INDEX idx_notification_due ON notification_outbox(next_attempt_at) WHERE status IN ('QUEUED','RETRY');

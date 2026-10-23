-- Gateway orders and immutable, server-priced checkout lines. No card data is stored.
CREATE TABLE checkout_order (
 id UUID PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES app_user(id),
 event_id BIGINT NOT NULL REFERENCES event(id),
 idempotency_key UUID NOT NULL,
 request_fingerprint VARCHAR(512) NOT NULL,
 status VARCHAR(24) NOT NULL CHECK (status IN ('CREATING','CREATE_UNKNOWN','PENDING','EXPIRED','CONFIRMED','REVIEW_REQUIRED')),
 amount BIGINT NOT NULL CHECK (amount > 0),
 currency VARCHAR(3) NOT NULL CHECK (currency = 'INR'),
 provider_order_id VARCHAR(80) UNIQUE,
 provider_payment_id VARCHAR(80) UNIQUE,
 admission_hold_id UUID,
 expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 confirmed_at TIMESTAMPTZ,
 last_checked_at TIMESTAMPTZ,
 UNIQUE(user_id,idempotency_key)
);
CREATE TABLE checkout_item (
 id BIGSERIAL PRIMARY KEY,
 order_id UUID NOT NULL REFERENCES checkout_order(id),
 seat_id BIGINT REFERENCES seat(id),
 ticket_type_id BIGINT REFERENCES admission_ticket_type(id),
 label VARCHAR(255) NOT NULL,
 quantity INT NOT NULL CHECK (quantity BETWEEN 1 AND 3),
 unit_amount BIGINT NOT NULL CHECK (unit_amount >= 0),
 CHECK ((seat_id IS NOT NULL AND ticket_type_id IS NULL AND quantity=1)
     OR (seat_id IS NULL AND ticket_type_id IS NOT NULL)),
 UNIQUE(order_id,seat_id), UNIQUE(order_id,ticket_type_id)
);
CREATE INDEX idx_checkout_user ON checkout_order(user_id,created_at DESC);
CREATE INDEX idx_checkout_pending ON checkout_order(created_at) WHERE status IN ('CREATING','CREATE_UNKNOWN','PENDING','EXPIRED');
CREATE INDEX idx_checkout_event ON checkout_order(event_id,user_id,status);

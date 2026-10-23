-- Durable full-refund requests; the checkout UUID is the permanent provider idempotency key.
CREATE TABLE refund_job (
 order_id UUID PRIMARY KEY REFERENCES checkout_order(id) ON DELETE CASCADE,
 payment_id VARCHAR(80) NOT NULL UNIQUE,
 amount BIGINT NOT NULL CHECK (amount > 0),
 currency VARCHAR(3) NOT NULL CHECK (currency='INR'),
 reason VARCHAR(32) NOT NULL CHECK (reason IN ('EVENT_CANCELLED','TICKETS_NOT_ISSUED')),
 status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RETRY','PENDING','PROCESSED','FAILED','REVIEW')),
 provider_refund_id VARCHAR(80) UNIQUE,
 failures INT NOT NULL DEFAULT 0,
 lease_token UUID,
 lease_until TIMESTAMPTZ,
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 last_error VARCHAR(120)
);
CREATE INDEX idx_refund_due ON refund_job(next_attempt_at) WHERE status IN ('QUEUED','RETRY','PENDING');

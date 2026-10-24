CREATE TABLE refund_operation (
 id BIGSERIAL PRIMARY KEY,
 order_id UUID NOT NULL REFERENCES checkout_order(id) ON DELETE CASCADE,
 actor VARCHAR(255) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

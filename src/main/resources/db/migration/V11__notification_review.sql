-- Keep notification history and review intent separate from ticket/payment records.
ALTER TABLE notification_outbox ADD COLUMN order_id UUID REFERENCES checkout_order(id) ON DELETE CASCADE;
ALTER TABLE notification_outbox ADD COLUMN event_change_id BIGINT REFERENCES event_change(id) ON DELETE CASCADE;
UPDATE notification_outbox n SET order_id=o.id FROM checkout_order o
 WHERE n.dedupe_key='booking:'||o.id::text OR n.dedupe_key LIKE 'refund:'||o.id::text||':%';
UPDATE notification_outbox n SET event_change_id=c.id FROM event_change c
 WHERE n.dedupe_key='change:'||c.id::text||':'||n.user_id::text;
ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_status_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_status_check
 CHECK(status IN ('QUEUED','RETRY','SENT','FAILED','SUPPRESSED'));
CREATE TABLE notification_operation (
 id BIGSERIAL PRIMARY KEY,
 notification_id UUID NOT NULL REFERENCES notification_outbox(id) ON DELETE CASCADE,
 action VARCHAR(16) NOT NULL CHECK(action IN ('RETRY','SUPPRESS')),
 actor VARCHAR(255) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

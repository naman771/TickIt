ALTER TABLE event ADD COLUMN cancellation_cutoff_hours integer CHECK(cancellation_cutoff_hours BETWEEN 1 AND 720);
-- Snapshot the policy promised at purchase; later policy edits do not remove that promise.
ALTER TABLE checkout_order ADD COLUMN cancellation_deadline timestamptz;
ALTER TABLE refund_job DROP CONSTRAINT refund_job_reason_check;
ALTER TABLE refund_job ADD CONSTRAINT refund_job_reason_check CHECK(reason IN ('EVENT_CANCELLED','TICKETS_NOT_ISSUED','CUSTOMER_CANCELLED'));
ALTER TABLE admission_ticket ADD COLUMN holder_id bigint REFERENCES app_user(id);
CREATE INDEX admission_ticket_holder ON admission_ticket(holder_id) WHERE holder_id IS NOT NULL;
CREATE TABLE ticket_transfer (
 id uuid PRIMARY KEY,
 ticket_id uuid NOT NULL REFERENCES admission_ticket(id) ON DELETE CASCADE,
 sender_id bigint NOT NULL REFERENCES app_user(id),
 recipient_id bigint NOT NULL REFERENCES app_user(id),
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','ACCEPTED','DECLINED','CANCELLED','EXPIRED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 resolved_at timestamptz,
 CHECK(sender_id<>recipient_id)
);
CREATE UNIQUE INDEX one_pending_ticket_transfer ON ticket_transfer(ticket_id) WHERE status='PENDING';
CREATE INDEX transfer_recipient ON ticket_transfer(recipient_id,created_at DESC);
CREATE INDEX transfer_sender ON ticket_transfer(sender_id,created_at DESC);

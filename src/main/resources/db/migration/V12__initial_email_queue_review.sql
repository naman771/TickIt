-- Delivery is currently disabled. Preserve existing backlog for explicit review before activation.
ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_status_check;
ALTER TABLE notification_outbox ADD CONSTRAINT notification_outbox_status_check
 CHECK(status IN ('HELD','QUEUED','RETRY','SENT','FAILED','SUPPRESSED'));
UPDATE notification_outbox SET status='HELD',last_error='Awaiting initial delivery review'
 WHERE status IN ('QUEUED','RETRY','FAILED') AND (lease_until IS NULL OR lease_until<clock_timestamp());
ALTER TABLE notification_operation DROP CONSTRAINT notification_operation_action_check;
ALTER TABLE notification_operation ADD CONSTRAINT notification_operation_action_check
 CHECK(action IN ('RETRY','SUPPRESS','RELEASE'));

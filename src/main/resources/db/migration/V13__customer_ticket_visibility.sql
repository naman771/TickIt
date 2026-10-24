-- Customer dashboard preferences never delete admissions, payments or refund history.
CREATE TABLE customer_hidden_event (
 user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 event_id BIGINT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
 hidden_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(user_id,event_id)
);

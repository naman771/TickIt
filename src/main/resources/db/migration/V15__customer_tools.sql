CREATE TABLE saved_event (
 user_id bigint NOT NULL REFERENCES app_user(id),
 event_id bigint NOT NULL REFERENCES event(id) ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(user_id,event_id)
);
CREATE TABLE customer_support_request (
 id uuid PRIMARY KEY,
 user_id bigint NOT NULL REFERENCES app_user(id),
 order_id uuid NOT NULL REFERENCES checkout_order(id) ON DELETE CASCADE,
 category varchar(24) NOT NULL CHECK(category IN ('PAYMENT','REFUND','ENTRY','EVENT','OTHER')),
 message varchar(3000) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED')),
 reply varchar(3000),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX customer_support_owner ON customer_support_request(user_id,created_at DESC);
CREATE TABLE confirmation_resend (
 id uuid PRIMARY KEY,
 user_id bigint NOT NULL REFERENCES app_user(id),
 order_id uuid NOT NULL REFERENCES checkout_order(id) ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX confirmation_resend_owner ON confirmation_resend(user_id,created_at DESC);

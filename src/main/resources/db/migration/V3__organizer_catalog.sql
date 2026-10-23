CREATE TABLE organizer_account (
 user_id BIGINT PRIMARY KEY REFERENCES app_user(id),
 display_name VARCHAR(120) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE event ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE event ADD COLUMN organizer_id BIGINT REFERENCES organizer_account(user_id);
ALTER TABLE event ADD COLUMN publication_status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' CHECK (publication_status IN ('DRAFT','PUBLISHED'));
ALTER TABLE event ADD COLUMN admission_mode VARCHAR(16) NOT NULL DEFAULT 'SEATED' CHECK (admission_mode IN ('SEATED','GENERAL','MIXED'));
ALTER TABLE event ADD COLUMN category VARCHAR(24) NOT NULL DEFAULT 'MUSIC';
ALTER TABLE event ADD COLUMN city VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE event ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata';
ALTER TABLE event ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD';
ALTER TABLE event ADD COLUMN sales_start TIMESTAMP;
ALTER TABLE event ADD COLUMN sales_end TIMESTAMP;
ALTER TABLE event ADD COLUMN per_order_limit INT NOT NULL DEFAULT 10 CHECK (per_order_limit BETWEEN 1 AND 10);
ALTER TABLE event ADD COLUMN guest_capacity INT NOT NULL DEFAULT 0 CHECK (guest_capacity BETWEEN 0 AND 100000);
ALTER TABLE event ADD COLUMN stage_label VARCHAR(80) NOT NULL DEFAULT 'Stage';
ALTER TABLE event ADD COLUMN stage_x INT NOT NULL DEFAULT 400;
ALTER TABLE event ADD COLUMN stage_y INT NOT NULL DEFAULT 20;
ALTER TABLE event ADD COLUMN layout_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE seat ADD COLUMN section_code VARCHAR(40) NOT NULL DEFAULT 'Main';
ALTER TABLE seat ADD COLUMN map_x INT;
ALTER TABLE seat ADD COLUMN map_y INT;
ALTER TABLE seat DROP CONSTRAINT IF EXISTS seat_venue_id_seat_row_seat_number_key;
ALTER TABLE seat ADD CONSTRAINT uq_seat_section_row_number UNIQUE (venue_id, section_code, seat_row, seat_number);
CREATE TABLE admission_pool (
 id BIGSERIAL PRIMARY KEY,
 event_id BIGINT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
 name VARCHAR(80) NOT NULL,
 capacity INT NOT NULL CHECK (capacity > 0 AND capacity <= 100000),
 sold INT NOT NULL DEFAULT 0 CHECK (sold >= 0 AND sold <= capacity),
 UNIQUE(event_id,name)
);
CREATE TABLE admission_ticket_type (
 id BIGSERIAL PRIMARY KEY,
 pool_id BIGINT NOT NULL REFERENCES admission_pool(id) ON DELETE CASCADE,
 name VARCHAR(80) NOT NULL,
 price NUMERIC(10,2) NOT NULL CHECK (price >= 0),
 quota INT NOT NULL CHECK (quota > 0),
 sold INT NOT NULL DEFAULT 0 CHECK (sold >= 0 AND sold <= quota),
 UNIQUE(pool_id,name)
);
CREATE TABLE admission_hold (
 id UUID PRIMARY KEY,
 event_id BIGINT NOT NULL REFERENCES event(id),
 ticket_type_id BIGINT NOT NULL REFERENCES admission_ticket_type(id),
 user_id BIGINT NOT NULL REFERENCES app_user(id),
 quantity INT NOT NULL CHECK (quantity BETWEEN 1 AND 10),
 expires_at TIMESTAMPTZ NOT NULL,
 released BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_event_owner_status ON event(organizer_id,publication_status);
CREATE INDEX idx_admission_hold_expiry ON admission_hold(expires_at);
CREATE INDEX idx_admission_hold_ticket ON admission_hold(ticket_type_id,expires_at) WHERE NOT released;

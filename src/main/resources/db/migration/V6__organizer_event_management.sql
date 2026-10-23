ALTER TABLE event DROP CONSTRAINT event_publication_status_check;
ALTER TABLE event ADD CONSTRAINT event_publication_status_check CHECK (publication_status IN ('DRAFT','PUBLISHED','CANCELLED','DELETED'));
ALTER TABLE event ADD COLUMN change_note VARCHAR(1000);
ALTER TABLE event ADD COLUMN schedule_changed_at TIMESTAMPTZ;
ALTER TABLE seat ADD COLUMN retired BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE seat DROP CONSTRAINT uq_seat_section_row_number;
CREATE UNIQUE INDEX uq_active_seat_label ON seat(venue_id,section_code,seat_row,seat_number) WHERE NOT retired;
ALTER TABLE checkout_order ADD COLUMN event_revision BIGINT NOT NULL DEFAULT 0;
UPDATE checkout_order o SET event_revision=e.layout_version FROM event e WHERE e.id=o.event_id;
CREATE TABLE event_change (
 id BIGSERIAL PRIMARY KEY,
 event_id BIGINT NOT NULL REFERENCES event(id),
 action VARCHAR(24) NOT NULL,
 reason VARCHAR(1000),
 previous_start TIMESTAMP NOT NULL,
 new_start TIMESTAMP NOT NULL,
 previous_timezone VARCHAR(64) NOT NULL,
 new_timezone VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_event_change_event ON event_change(event_id,created_at);

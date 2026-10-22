-- V1__init_schema.sql
-- Core schema for the TickIt real-time booking platform

-- ── Users ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS app_user (
    id          BIGSERIAL    PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    full_name   VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- ── Events ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS event (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(512) NOT NULL,
    description TEXT,
    start_time  TIMESTAMP    NOT NULL,
    end_time    TIMESTAMP,
    image_url   VARCHAR(1024),
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

-- ── Venues ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS venue (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    location    VARCHAR(512),
    event_id    BIGINT       NOT NULL REFERENCES event(id) ON DELETE CASCADE
);

-- ── Seats ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS seat (
    id          BIGSERIAL    PRIMARY KEY,
    seat_row    VARCHAR(10)  NOT NULL,
    seat_number INT          NOT NULL,
    price       NUMERIC(10,2) NOT NULL DEFAULT 0,
    venue_id    BIGINT       NOT NULL REFERENCES venue(id) ON DELETE CASCADE,
    UNIQUE (venue_id, seat_row, seat_number)
);

-- ── Bookings ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS booking (
    id          BIGSERIAL    PRIMARY KEY,
    seat_id     BIGINT       NOT NULL REFERENCES seat(id),
    user_id     BIGINT       NOT NULL REFERENCES app_user(id),
    event_id    BIGINT       NOT NULL REFERENCES event(id),
    booked_at   TIMESTAMP    NOT NULL DEFAULT now(),
    status      VARCHAR(32)  NOT NULL DEFAULT 'CONFIRMED',
    version     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (seat_id, event_id)           -- one booking per seat per event
);

-- ── Indexes ──────────────────────────────────────────────
CREATE INDEX idx_booking_event   ON booking(event_id);
CREATE INDEX idx_booking_user    ON booking(user_id);
CREATE INDEX idx_seat_venue      ON seat(venue_id);
CREATE INDEX idx_venue_event     ON venue(event_id);

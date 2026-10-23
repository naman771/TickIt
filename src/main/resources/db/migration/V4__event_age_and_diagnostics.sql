ALTER TABLE event ADD COLUMN minimum_age integer CHECK (minimum_age BETWEEN 1 AND 120);
CREATE TABLE diagnostic_claim (
    run_id uuid NOT NULL,
    seat integer NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seat)
);

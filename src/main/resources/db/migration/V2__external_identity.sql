-- Provider identity is independent of mutable email addresses.
ALTER TABLE app_user ADD COLUMN auth_issuer VARCHAR(512);
ALTER TABLE app_user ADD COLUMN auth_subject VARCHAR(255);
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE app_user ADD CONSTRAINT uq_user_external_identity UNIQUE (auth_issuer, auth_subject);

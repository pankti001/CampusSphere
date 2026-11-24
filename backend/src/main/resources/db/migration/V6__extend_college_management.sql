ALTER TABLE public.colleges
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT ck_colleges_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,29}$'),
    ADD CONSTRAINT ck_colleges_name CHECK (name ~ '[^[:space:]]');

ALTER TABLE public.colleges ALTER COLUMN is_active SET DEFAULT false;

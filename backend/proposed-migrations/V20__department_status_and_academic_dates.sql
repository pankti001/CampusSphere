-- PROPOSED, NOT APPLIED. Needs the project owner's approval before it is run on any database.
--
-- Why: the College Admin audit (27 Nov 2026) found two requested features the schema cannot hold:
--   1. Departments cannot be deactivated (no status column; deleting is blocked by foreign keys, and history
--      such as programs, assignments and attendance must be kept).
--   2. Academic years and semesters have no start or end dates, so "current" vs "past" periods and date
--      validation cannot be shown.
--
-- Effect on existing data: none is changed. Every existing department becomes active (the default), and every
-- existing year and semester simply has no dates yet (both columns allow NULL). No row is deleted or rewritten.
-- Recovery: take a backup before applying (as for V17–V19). The change is additive; if it ever had to be undone,
-- the new columns and constraints can be dropped without affecting any other table.

ALTER TABLE public.departments
    ADD COLUMN is_active boolean NOT NULL DEFAULT true;

ALTER TABLE public.academic_years
    ADD COLUMN start_date date,
    ADD COLUMN end_date date,
    ADD CONSTRAINT ck_academic_year_dates CHECK (
        (start_date IS NULL AND end_date IS NULL)
        OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date > start_date
            AND end_date <= start_date + 400));

ALTER TABLE public.academic_semesters
    ADD COLUMN start_date date,
    ADD COLUMN end_date date,
    ADD CONSTRAINT ck_academic_semester_dates CHECK (
        (start_date IS NULL AND end_date IS NULL)
        OR (start_date IS NOT NULL AND end_date IS NOT NULL AND end_date > start_date
            AND end_date <= start_date + 400));

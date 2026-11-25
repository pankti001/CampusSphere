ALTER TABLE public.faculty_access_requests
    ADD COLUMN requested_college_id bigint REFERENCES public.colleges (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_faculty_request_college_reviewer
        FOREIGN KEY (requested_college_id, reviewer_id)
        REFERENCES public.users (college_id, id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_faculty_request_college_binding CHECK (
        requested_college_id IS NULL
        OR (status = 'APPROVED' AND college_id IS NOT DISTINCT FROM requested_college_id)
        OR (status IN ('PENDING', 'REJECTED') AND college_id IS NULL)
    );

CREATE INDEX ix_faculty_request_target_status
    ON public.faculty_access_requests (requested_college_id, status, id);

CREATE INDEX ix_faculty_request_target_reviewer
    ON public.faculty_access_requests (requested_college_id, reviewer_id)
    WHERE reviewer_id IS NOT NULL;

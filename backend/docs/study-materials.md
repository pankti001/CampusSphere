# Study Materials

Study materials reuse the existing tenant, Faculty assignments and Student enrollments. V16 is an additive proposal in
`backend/proposed-migrations/V16__create_study_materials.sql`. It requires V1–V15 and has not been applied.

## Rules

| Role | May do |
| --- | --- |
| Faculty | List their own active assignments; upload to one of them; list, view, download, edit and delete their own materials (not removed or deleted ones). |
| Student | List, view and download `PUBLISHED` materials whose assignment matches their current enrollment (college, department, program, academic year, semester and division). |
| College Admin | List, view, filter and download every material in their own college; see the activity log; hide (with reason), restore, or remove (with reason). |
| Super Admin / Admin | No access. |

College, Faculty and Student identity always come from the JWT principal. Request parameters such as `collegeId` or
`facultyId` are ignored. A material outside the caller's scope returns `404 MATERIAL_NOT_FOUND`.

Status flow: `PUBLISHED` → `HIDDEN` (Admin, reversible) → `PUBLISHED` (Admin restore); `PUBLISHED`/`HIDDEN` → `REMOVED`
(Admin, final) or `DELETED` (Faculty, final). Records, files and history are kept after removal or deletion.

## Files

* Formats: PDF, DOCX, PPTX, XLSX and UTF-8 text, 1 byte to 10 MiB.
* The extension, the declared MIME type (or `application/octet-stream`) and the content are all checked: PDF header and
  `%%EOF`, Office packages must contain `[Content_Types].xml` and their main part and no `vbaProject.bin`, text must be
  valid UTF-8 without control characters. Office packages are checked by reading the ZIP directory only.
* The client file name is cleaned (no paths, control or reserved characters, at most 200 characters) and stored only as
  metadata. Files are written under `app.materials.directory` (default `~/.campussphere/private-materials`) using a random
  UUID name, through a temporary file and an atomic move. The file is deleted again if the transaction does not commit.
* The same file (SHA-256) cannot be active twice in one assignment (`409 MATERIAL_DUPLICATE`).
* Downloads are `attachment`, `no-store`, `nosniff`, with the stored MIME type.

## REST routes

| Role | Routes |
| --- | --- |
| Faculty | `GET /api/faculty/materials/assignments`; `GET /api/faculty/materials?subjectId&divisionId&type&status&q`; `GET …/{id}`; `POST /api/faculty/materials` (multipart: `assignmentId`, `title`, `description`, `topic`, `type`, `file`); `PATCH …/{id}` (`title`, `description`, `topic`, `type`); `DELETE …/{id}`; `GET …/{id}/file` |
| Student | `GET /api/student/materials?subjectId&type&q`; `GET …/{id}`; `GET …/{id}/file` |
| College Admin | `GET /api/college/materials?subjectId&divisionId&facultyId&type&status&q`; `GET …/{id}`; `GET …/{id}/file`; `GET …/{id}/events`; `POST …/{id}/moderation` (`{"action":"HIDE|RESTORE|REMOVE","note":"…"}`) |

Types: `NOTES`, `SLIDES`, `ASSIGNMENT`, `QUESTION_BANK`, `LAB_MANUAL`, `REFERENCE`, `OTHER`.
Errors: `MATERIAL_FILE_INVALID` (400), `INVALID_REQUEST` (400), `MATERIAL_NOT_FOUND` (404), `MATERIAL_DUPLICATE` (409),
`MATERIAL_STATE_CONFLICT` (409).

## Live acceptance gate

Apply V16 to `campus_sphere_dev` only after approval and a fresh backup. Then, on Pixel 6 with real accounts:
Faculty uploads a PDF to an assigned class → metadata row and private file exist → Student enrolled in that class sees
and downloads the same file → College Admin filters, hides (Student no longer sees it), restores, and the activity log
shows UPLOAD/HIDE/RESTORE → a Student and a College Admin from a second college get `404` for that material → results
persist after backend and app restart.

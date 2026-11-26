package com.campussphere.backend.security;

import org.springframework.http.HttpStatus;

public enum ApiError {
    MATERIAL_NOT_FOUND(HttpStatus.NOT_FOUND, "Material or assignment unavailable."),
    MATERIAL_FILE_INVALID(HttpStatus.BAD_REQUEST, "Upload a PDF, Word, PowerPoint, Excel or plain-text file up to 10 MB."),
    MATERIAL_DUPLICATE(HttpStatus.CONFLICT, "This file is already shared with this class."),
    MATERIAL_STATE_CONFLICT(HttpStatus.CONFLICT, "This material can no longer be changed. Refresh and try again."),
    ANNOUNCEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "The announcement was not found."),
    ANNOUNCEMENT_TARGET_INVALID(HttpStatus.BAD_REQUEST, "Choose an audience you are allowed to address in your college."),
    ANNOUNCEMENT_SCHEDULE_INVALID(HttpStatus.BAD_REQUEST, "The expiry must be in the future and after the publication time."),
    ANNOUNCEMENT_STATE_CONFLICT(HttpStatus.CONFLICT, "This announcement can no longer be changed this way. Refresh and try again."),
    TIMETABLE_NOT_FOUND(HttpStatus.NOT_FOUND, "The timetable or timetable entry was not found."),
    TIMETABLE_SCOPE_INVALID(HttpStatus.BAD_REQUEST, "Choose a semester and division from your college's academic structure."),
    TIMETABLE_ENTRY_INVALID(HttpStatus.BAD_REQUEST, "Enter a valid day, start and end time in 5-minute steps and the required lesson details."),
    TIMETABLE_ASSIGNMENT_INVALID(HttpStatus.BAD_REQUEST, "Choose a subject and faculty member currently assigned to this division and semester."),
    TIMETABLE_CLASSROOM_INVALID(HttpStatus.BAD_REQUEST, "Choose an active classroom of your college."),
    TIMETABLE_CONFLICT(HttpStatus.CONFLICT, "This time overlaps another class, break, faculty booking or classroom booking."),
    TIMETABLE_NOT_READY(HttpStatus.CONFLICT, "Fix the listed problems before publishing."),
    TIMETABLE_DRAFT_EXISTS(HttpStatus.CONFLICT, "A draft already exists for this division and semester."),
    TIMETABLE_STATE_CONFLICT(HttpStatus.CONFLICT, "This timetable changed or can no longer be changed this way. Refresh and try again."),
    TIMETABLE_IMPORT_INVALID(HttpStatus.BAD_REQUEST, "Fix the problems shown in the preview, then upload the corrected file."),
    TIMETABLE_IMPORT_CHANGED(HttpStatus.CONFLICT, "This file differs from the one you previewed. Preview it again before importing."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "The notification was not found."),
    CLASSROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "The classroom was not found."),
    CLASSROOM_INVALID(HttpStatus.BAD_REQUEST, "Enter a classroom code, a name and an optional capacity up to 2000."),
    CLASSROOM_CODE_TAKEN(HttpStatus.CONFLICT, "Another classroom already uses this code."),
    INVALID_AUTHENTICATION(HttpStatus.UNAUTHORIZED, "Authentication is required or invalid."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You do not have permission to perform this action."),
    VERIFICATION_REQUIRED(HttpStatus.FORBIDDEN, "Account verification is required."),
    DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The database is temporarily unavailable."),
    REGISTRATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Application not found."),
    REGISTRATION_CONFLICT(HttpStatus.CONFLICT, "Application or identity conflict. Refresh before reviewing."),
    REGISTRATION_EXPIRED(HttpStatus.CONFLICT, "This application expired."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Check the request fields and try again."),
    COLLEGE_NOT_FOUND(HttpStatus.NOT_FOUND, "The college was not found."),
    COLLEGE_CODE_CONFLICT(HttpStatus.CONFLICT, "A college already uses that code."),
    COLLEGE_INACTIVE(HttpStatus.CONFLICT, "Activate the college before issuing invitations."),
    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "The invitation was not found."),
    INVITATION_CONFLICT(HttpStatus.CONFLICT, "The invitation cannot be changed in its current state."),
    INVITATION_INVALID(HttpStatus.BAD_REQUEST, "This invitation cannot be accepted."),
    ACADEMIC_YEAR_NOT_FOUND(HttpStatus.NOT_FOUND, "The academic year was not found."),
    ACADEMIC_YEAR_CONFLICT(HttpStatus.CONFLICT, "This college already has that academic year."),
    DIVISION_NOT_FOUND(HttpStatus.NOT_FOUND, "The division was not found in this program and academic year."),
    DIVISION_CONFLICT(HttpStatus.CONFLICT, "This program already uses that division code in this academic year."),
    SUBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "The subject was not found in this program and semester."),
    SUBJECT_CONFLICT(HttpStatus.CONFLICT, "This program already uses that subject code in this semester."),
    SEMESTER_NOT_FOUND(HttpStatus.NOT_FOUND, "The semester was not found in this program and academic year."),
    SEMESTER_CONFLICT(HttpStatus.CONFLICT, "This program already has that semester number in this academic year."),
    PROGRAM_NOT_FOUND(HttpStatus.NOT_FOUND, "The program was not found in this department."),
    PROGRAM_CODE_CONFLICT(HttpStatus.CONFLICT, "This college already uses that program code."),
    DEPARTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "The department was not found."),
    DEPARTMENT_CODE_CONFLICT(HttpStatus.CONFLICT, "A department already uses that code."),
    REGISTRATION_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "Public registration is available only to Students and Faculty applicants."),
    ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT, "An account already uses the supplied email or phone."),
    STUDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "The student was not found."),
    STUDENT_DEPARTMENT_CONFLICT(HttpStatus.CONFLICT, "End the current enrollment before changing the department."),
    STUDENT_ENROLLMENT_INVALID(HttpStatus.BAD_REQUEST, "Choose a matching department, program, year, semester and division."),
    STUDENT_ENROLLMENT_CONFLICT(HttpStatus.CONFLICT, "This student already has a current enrollment."),
    STUDENT_ENROLLMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "The current enrollment was not found."),
    STUDENT_COLLEGE_UNAVAILABLE(HttpStatus.NOT_FOUND, "No active college uses that code."),
    STUDENT_AFFILIATION_NOT_FOUND(HttpStatus.NOT_FOUND, "The student affiliation request was not found."),
    STUDENT_AFFILIATION_CONFLICT(HttpStatus.CONFLICT, "The student affiliation request conflicts with the current status."),
    STUDENT_ADMISSION_UNCONFIRMED(HttpStatus.BAD_REQUEST, "Confirm the college admission record before approving."),
    FACULTY_NOT_FOUND(HttpStatus.NOT_FOUND, "The faculty member was not found."),
    FACULTY_DEPARTMENT_CONFLICT(HttpStatus.CONFLICT, "End active assignments before changing the department."),
    FACULTY_ASSIGNMENT_INVALID(HttpStatus.BAD_REQUEST, "Choose a faculty member's department and a matching program, year, semester, division, and subject."),
    FACULTY_ASSIGNMENT_CONFLICT(HttpStatus.CONFLICT, "This faculty member already has that active subject assignment in this division."),
    FACULTY_ASSIGNMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "The active assignment was not found."),
    FACULTY_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "The Faculty request was not found."),
    FACULTY_COLLEGE_UNAVAILABLE(HttpStatus.BAD_REQUEST, "Enter an active college's registration code."),
    FACULTY_EMPLOYMENT_UNCONFIRMED(HttpStatus.BAD_REQUEST, "Verify the applicant's employment before approval."),
    FACULTY_REQUEST_CONFLICT(HttpStatus.CONFLICT, "The Faculty request cannot be changed in its current state."),
    ATTENDANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "The attendance session or record was not found."),
    ATTENDANCE_SESSION_CONFLICT(HttpStatus.CONFLICT, "A session already exists for that lecture."),
    ATTENDANCE_STATE_CONFLICT(HttpStatus.CONFLICT, "The session changed or can no longer be edited. Refresh before continuing."),
    ATTENDANCE_ROSTER_INVALID(HttpStatus.BAD_REQUEST, "Use only the session roster and mark every Student before submitting."),
    ATTENDANCE_SESSION_INVALID(HttpStatus.BAD_REQUEST, "Enter a valid lecture date, number and time for your assignment."),
    ATTENDANCE_POLICY_INVALID(HttpStatus.BAD_REQUEST, "Use valid thresholds and correction hours.");

    private final HttpStatus status;
    private final String message;

    ApiError(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public Body body() {
        return new Body(name(), message);
    }

    public RuntimeException exception() {
        return new Failure(this);
    }

    public record Body(String code, String message) {}

    public static final class Failure extends RuntimeException {
        private final ApiError error;

        private Failure(ApiError error) {
            super(error.message);
            this.error = error;
        }

        public ApiError error() {
            return error;
        }
    }
}

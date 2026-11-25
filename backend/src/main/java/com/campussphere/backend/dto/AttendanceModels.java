package com.campussphere.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class AttendanceModels {
    private AttendanceModels() {}

    public enum Mark { PRESENT, ABSENT, LATE, EXCUSED }
    public enum State { DRAFT, SUBMITTED, LOCKED }
    public enum Severity { NORMAL, WARNING, CRITICAL }

    public record Create(@Positive long assignmentId, @NotNull LocalDate date, @Min(1) @Max(99) int lectureNumber,
                         LocalTime startTime, LocalTime endTime) {}
    public record MarkInput(@Positive long studentId, @NotNull Mark status) {}
    public record Marks(@NotNull @PositiveOrZero Long revision,
                        @NotNull @Size(max = 2000) List<@NotNull @Valid MarkInput> records) {}
    public record Revision(@NotNull @PositiveOrZero Long revision) {}
    public record CorrectionInput(@NotNull @PositiveOrZero Long revision, @NotNull Mark status,
                                  @NotBlank @Size(max = 500) String reason) {}
    public record Policy(@NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
                         BigDecimal warningThreshold,
                         @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
                         BigDecimal criticalThreshold,
                         @NotNull Boolean countLate, @NotNull Boolean excludeExcused,
                         @NotNull @Min(0) @Max(720) Integer correctionHours) {
        public static final Policy DEFAULT = new Policy(BigDecimal.valueOf(75), BigDecimal.valueOf(65), true, true, 48);
    }

    public record Assignment(long id, String subject, String division, String semester, String program) {}
    public record Session(long id, long assignmentId, State status, long revision, LocalDate date, int lectureNumber,
                          LocalTime startTime, LocalTime endTime, OffsetDateTime submittedAt, String subject,
                          String division, String faculty, String department, String program, String semester) {}
    public record RosterRow(long studentId, String name, Mark status) {}
    public record SessionDetail(Session session, List<RosterRow> roster) {}
    public record Correction(long id, long studentId, String actor, Mark previousStatus, Mark newStatus,
                             String reason, OffsetDateTime correctedAt) {}
    public record Summary(long present, long absent, long late, long excused, long attended, long eligible,
                          BigDecimal percentage, Severity severity) {}
    public record SubjectSummary(long subjectId, String subject, Summary summary) {}
    public record StudentSummary(long studentId, String student, Summary summary) {}
    public record HistoryRow(long sessionId, LocalDate date, int lectureNumber, String subject, String division,
                             Mark status) {}
    public record Alert(long id, long studentId, String student, Severity severity, BigDecimal percentage,
                        OffsetDateTime createdAt, OffsetDateTime resolvedAt) {}
    public record StudentOverview(Policy policy, Summary overall, List<SubjectSummary> subjects,
                                  List<HistoryRow> history, List<Alert> alerts) {}
    public record Report(Policy policy, Summary overall, List<SubjectSummary> subjects, List<StudentSummary> students,
                         List<Session> sessions) {}
    public record Option(long id, String label) {}
    public record ReportOptions(Map<String, List<Option>> filters) {}
    public record Filter(LocalDate from, LocalDate to, Long departmentId, Long programId, Long semesterId,
                         Long divisionId, Long subjectId, Long studentId, Long facultyId, int page) {}
}

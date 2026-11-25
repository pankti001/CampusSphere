# CampusSphere final demonstration guide

## One-click Pixel 6 walkthrough

Double-click [CampusSphere-Demo.cmd](CampusSphere-Demo.cmd) from the Android project folder. The launcher builds the debug and test APKs offline, starts or reuses Pixel_6 on emulator-5554, checks its identity, and installs the debug packages. It runs the Student Management and Enrollment walkthrough by default. The screens label intercepted Retrofit responses as synthetic. The launcher saves the refreshed captures under app/build/reports/student-enrollment-polished-pixel6. The original captures are available under app/build/reports/student-enrollment-demo-pixel6 for comparison.

To reuse built APKs from PowerShell:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Start-CampusSphereDemo.ps1 -SkipBuild

Use this command to repeat Faculty Management and Subject Assignment and save its captures under app/build/reports/faculty-management-demo-pixel6.

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Start-CampusSphereDemo.ps1 -Phase FacultyManagement

To run the previous Academic Years and Semesters demonstration and save its 22 captures under app/build/reports/academic-periods-demo-pixel6:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Start-CampusSphereDemo.ps1 -Phase AcademicPeriods

To repeat the Divisions and Subjects demonstration and save its 26 captures under app/build/reports/divisions-subjects-demo-pixel6:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Start-CampusSphereDemo.ps1 -Phase DivisionsSubjects

Use the regular debug app for a live walkthrough only after the target backend, schema, accounts and test writes have separate approval. The emulator's real API base is http://10.0.2.2:8080/. The one-click synthetic mode exercises the actual Compose screens and Retrofit request handling without the backend.

## Student Management and Enrollment presenter walkthrough

1. Open **Request college affiliation** as the synthetic Student applicant. Demonstrate code validation, submit `CAMPUS-A`, and show that admission review is pending.
2. Open **Manage students** as College Admin. Show loading, outage, retry, and the empty directory. In **Affiliation requests**, open **Student Sample**, confirm the admission record, and approve. Reject the second synthetic request.
3. Find the approved student through the directory search. Open the profile, show the department validation, and set **Science** as the department.
4. Select **Biology program → Academic Year 2026 → First semester → Division A**, review, and save. Attempt a second current enrollment to show the duplicate guard.
5. Open the synthetic Student dashboard to see the current college, department, program, year, semester and division. End the enrollment from the admin profile. Show it in history and reopen the student dashboard to confirm the empty state.

## Faculty onboarding review walkthrough

Run `.\Start-CampusSphereDemo.ps1 -Phase FacultyOnboarding` for the Pixel 6 review walkthrough. It uses labeled synthetic Retrofit responses for the pending queue, employment confirmation, approval, rejection and the empty queue. The launcher exports the captures to `app/build/reports/faculty-onboarding-demo-pixel6`.

For a live rehearsal, first approve a dedicated PostgreSQL target with verified V1–V13, the proposed V14 migration, and two synthetic colleges and accounts. Use the regular debug app pointed at that backend. Register a Faculty applicant with the first college's code and confirm pending access does not open the Faculty dashboard. Verify that the second College Admin and the legacy Admin cannot decide that request. Approve with the first College Admin after verifying institutional identity and employment. Reload the applicant session and verify the only granted role is Faculty, with access limited to the approved college and assignments. Also exercise rejection, reapplication to the same college, and competing decisions. Keep the rehearsal and its accounts separate from development.

The [Faculty PostgreSQL runbook](backend/docs/faculty-onboarding-rehearsal.md) specifies the isolated target, migration preflight, rollback-only HTTP test, and separate live emulator checks.

## Faculty Management and Subject Assignment presenter walkthrough

1. Open **Manage faculty** as a College Admin. Show loading, the simulated outage, **Retry**, and the empty directory. Refresh to see the synthetic faculty member and use search.
2. Open **Faculty Example**. Attempt to add an assignment before setting a department and show the validation message. Set the department to **Science**.
3. Select **Biology program → Academic Year 2026 → First semester → Division A → Biology Lab**. Review the complete scope and save. Attempt the same assignment again and show the duplicate message.
4. Open the synthetic Faculty dashboard. Show the assigned subject, division, semester, program, department and academic year.
5. Return to the synthetic College Admin profile and confirm ending the assignment. Reopen the Faculty dashboard to show its empty state.

## Divisions and Subjects presenter walkthrough

1. Open **Manage departments → Science → Manage programs → Biology → Manage semesters** from the College Admin dashboard.
2. In **Select academic year**, use **Manage divisions** on the 2026–2027 year. Show loading, a simulated service outage, **Retry**, and the empty list.
3. Open **Add division**. Show required-field validation, save **Division A**, and demonstrate how a duplicate code is handled. Search for a value with no matches, clear the search, and edit the saved division.
4. Return to **Select academic year** and open the year card to show its semesters. Use **Manage subjects** on **Semester 1**.
5. Repeat the loading, error, retry, empty, validation, add, duplicate, search and edit steps with **Biology Lab**. Return through Semesters, the year picker, Programs and Departments to the dashboard.

## Academic Years and Semesters presenter walkthrough

1. Show the College Admin dashboard and open **Academic years**. Show its initial empty state.
2. Open **Add academic year**, enter an invalid nonconsecutive range, and show the validation message. Correct the range to `2026-2027`, add the year, search the list, and edit the year name.
3. Return to the dashboard and open **Manage departments → Science → Manage programs → Biology → Manage semesters**. Select the edited academic year.
4. Show semester loading, a simulated service error, Retry, and the empty semester list.
5. Add a semester. Show the invalid number error for `0` and save the corrected number `1`. Show duplicate rejection in the same program/year and cancel the attempted duplicate.
6. Search for semesters, edit the saved semester, show success, and navigate back through the year picker, Programs, Departments and dashboard.

## Evidence and boundaries

- The isolated Student run used the real Android Retrofit client, Spring Boot and PostgreSQL 19 on `campus_sphere_student_e2e`. Registration, affiliation, admission confirmation, department selection, enrollment, tenant and role denials, and retrieval after backend and app restarts passed. On the controlled rerun, stopping Spring Boot produced an error and hid the enrollment on Pixel 6. **Retry** restored it after Spring Boot restarted. Ending enrollment preserved the record ID, and a further backend and app restart showed that record in College Admin history and an empty current-enrollment view for the Student. The final read-only check found two approved affiliations, zero current enrollments, and one historical enrollment.
- The live walkthrough uses production Compose screens in an instrumentation activity after real Retrofit login. The separate one-click walkthrough continues to use labeled synthetic responses. Review [13 live captures](app/build/reports/student-enrollment-live-pixel6/final-contact-sheet.png), the [initial live test output](app/build/reports/student-enrollment-live-pixel6/live-verification-results.txt), and the [successful live rerun](app/build/reports/student-enrollment-live-pixel6/live-resume-results.txt). Capture 08 shows the persisted current enrollment. Captures 09–13 show the outage, recovery, and retained history. The isolated test backend is stopped after verification.
- Clearly label synthetic screenshots in the submission. Record the Android debug build, offline API and service tests, and Pixel instrumentation results separately from database persistence evidence.
- The Student build passed 41 focused offline backend tests and 34 Android JVM tests. The synthetic Student walkthrough covers the pending request, an explicit admission confirmation, approval and rejection, department placement, enrollment and history, and the enrolled and empty Student dashboard states.
- The Faculty build passed 27 focused offline backend tests and 30 Android JVM tests. On Pixel 6, the Faculty walkthrough checks the required academic selections and the duplicate conflict through intercepted Retrofit calls. The earlier academic regression suite covers Departments, Programs, Academic Years, Semesters, Divisions and Subjects.
- The Divisions and Subjects build passed 18 focused offline backend tests and 27 Android JVM tests. Its one-click walkthrough and the separate Department, Program, and Academic Years and Semesters regression tests all passed on Pixel 6. The phase folder contains 26 full-resolution captures and a contact sheet.
- The staged V10 migration creates academic years and semesters. The staged V11 migration adds Divisions and Subjects. Live persistence requires approval to inspect the history, verify prerequisites, back up, and apply the approved migrations to `127.0.0.1:5432/campus_sphere_dev`.
- The staged V12 migration adds faculty department profiles and a separate assignment history. The live workflow also requires an active, verified Faculty account already associated with the approved college. The legacy Faculty approval flow produces unassigned accounts and requires separate college onboarding work before those accounts become eligible.
- The staged V13 migration adds the student affiliation queue, department profiles and enrollment history. It depends on V9–V12 and the earlier college, department, and identity schema. Live verification requires an approved database rollout and authorization to run the affiliation and enrollment workflows. A College Admin must verify the student against college admission records before approving affiliation.
- Stop for a review after the demonstration. Continue only on approval.

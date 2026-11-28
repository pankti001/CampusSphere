package com.campussphere.app.presentation.college

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.campussphere.app.ui.brand.CampusIcons

/*
 * Wording and small visual helpers for the student screens. Layout itself comes from the shared page kit
 * (com.campussphere.app.ui.brand), so these only decide what each page is called and which icon it uses.
 */

/** The enrollment steps that come after the department, in order. */
internal fun enrollmentStep(page: StudentPage): Int? = when (page) {
    StudentPage.PROGRAM -> 1
    StudentPage.YEAR -> 2
    StudentPage.SEMESTER -> 3
    StudentPage.DIVISION -> 4
    StudentPage.REVIEW -> 5
    else -> null
}

internal const val ENROLLMENT_STEPS = 5

internal fun studentPageTitle(page: StudentPage): String = when (page) {
    StudentPage.DIRECTORY -> "Students"
    StudentPage.PROFILE -> "Student profile"
    StudentPage.DEPARTMENT -> "Set department"
    StudentPage.PROGRAM, StudentPage.YEAR, StudentPage.SEMESTER, StudentPage.DIVISION -> "Enroll student"
    StudentPage.REVIEW -> "Review enrollment"
    StudentPage.DASHBOARD -> "Student dashboard"
    StudentPage.REQUEST -> "Join your college"
    StudentPage.QUEUE -> "Join requests"
}

/** Line under the title: what the page is for, or which student the admin is working on. */
internal fun studentPageSubtitle(page: StudentPage, studentName: String?): String? = when (page) {
    StudentPage.DIRECTORY -> "Find students and manage enrollment"
    StudentPage.QUEUE -> "Students asking to join your college"
    StudentPage.REQUEST -> "Ask your college to confirm your admission"
    StudentPage.PROFILE, StudentPage.DEPARTMENT, StudentPage.PROGRAM, StudentPage.YEAR,
    StudentPage.SEMESTER, StudentPage.DIVISION, StudentPage.REVIEW -> studentName
    StudentPage.DASHBOARD -> null
}

/** Heading above a picker step (and the review), with the step number once the department is set. */
internal fun studentStepHeading(page: StudentPage): String = listOfNotNull(
    enrollmentStep(page)?.let { "Step $it of $ENROLLMENT_STEPS" },
    when (page) {
        StudentPage.DEPARTMENT -> "Choose a department"
        StudentPage.PROGRAM -> "Choose a program"
        StudentPage.YEAR -> "Choose an academic year"
        StudentPage.SEMESTER -> "Choose a semester"
        StudentPage.DIVISION -> "Choose a division"
        StudentPage.REVIEW -> "Check the details"
        else -> "Choose"
    }
).joinToString(" · ")

internal fun studentStepIcon(page: StudentPage): ImageVector = when (page) {
    StudentPage.DEPARTMENT -> CampusIcons.Building
    StudentPage.YEAR, StudentPage.SEMESTER -> CampusIcons.Calendar
    StudentPage.DIVISION -> CampusIcons.Users
    else -> CampusIcons.Book
}

/** A bordered block in the kit's style; clickable when [onClick] is set. */
@Composable
internal fun StudentPanel(
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.medium
    val color = MaterialTheme.colorScheme.surfaceContainerLowest
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    if (onClick == null) {
        Surface(Modifier.fillMaxWidth(), shape = shape, color = color, border = border) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    } else {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = shape, color = color, border = border) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

internal fun studentInitials(name: String) =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }

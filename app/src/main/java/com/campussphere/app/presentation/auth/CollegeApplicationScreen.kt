package com.campussphere.app.presentation.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.campussphere.app.data.remote.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.HttpException

internal fun validRegistrationPassword(password: String, confirmation: String) =
    password.isNotBlank() && password.length >= 12 && password.toByteArray().size <= 72 && password == confirmation

internal fun registrationSubmissionError(failure: Exception): String {
    val recovery = "Both password fields were cleared."
    return when ((failure as? HttpException)?.code()) {
        400 -> "The application was rejected. Check the form details. $recovery Re-enter your password to try again."
        429, 503 -> "The service could not accept the application right now. $recovery Re-enter your password to try again later."
        else -> "Submission could not be confirmed. $recovery Check your connection and re-enter your password to try again."
    }
}

@Composable
fun CollegeApplicationScreen(api: CollegeRegistrationApi, back: () -> Unit) {
    val labels = listOf("College name","Proposed college code","Official website (optional)","Administrator full name",
        "Institutional email","Position at college","Password","Confirm password")
    val fields = remember { mutableStateListOf("","","","","","","","") }
    var confirm by remember { mutableStateOf(false) }
    var receipt by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun exit() { fields[6]=""; fields[7]=""; back() }
    BackHandler { if (!busy) exit() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick=::exit, enabled=!busy) { Text("Back to login") }
        Text(if (receipt) "Submission received" else "Register Your College",style=MaterialTheme.typography.headlineMedium)
        if (receipt) {
            Text("Eligible applications are reviewed by a Super Admin. You can sign in after approval.")
        } else {
            Text("Register your institution and its first administrator. Pending applications expire after 30 days.")
            labels.forEachIndexed { i,label ->
                OutlinedTextField(fields[i], { fields[i]=it },label={ Text(label) },modifier=Modifier.fillMaxWidth(),
                    singleLine=true,enabled=!busy,
                    keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=when(i) {
                        6,7 -> androidx.compose.ui.text.input.KeyboardType.Password
                        4 -> androidx.compose.ui.text.input.KeyboardType.Email
                        2 -> androidx.compose.ui.text.input.KeyboardType.Uri
                        else -> androidx.compose.ui.text.input.KeyboardType.Text
                    }),visualTransformation=if (i>=6) PasswordVisualTransformation() else VisualTransformation.None)
            }
            Text("Use at least 12 characters and at most 72 UTF-8 bytes.")
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Button(enabled=!busy,onClick={
                val valid=fields[0].trim().length in 1..150 && fields[1].trim().matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,29}")) &&
                    (fields[2].isBlank() || (fields[2].length<=250 && fields[2].matches(Regex("https?://[^\\s/@?#]+(?:/[^\\s]*)?")))) &&
                    fields[3].trim().length in 1..100 && fields[4].trim().length<=150 &&
                    android.util.Patterns.EMAIL_ADDRESS.matcher(fields[4].trim()).matches() && fields[5].trim().length in 1..100
                if (!valid) error="Check the institution and administrator details."
                else if (!validRegistrationPassword(fields[6],fields[7])) error="Check password length and confirmation."
                else { error=null; confirm=true }
            }) { Text(if (busy) "Submitting…" else "Review submission") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Submit college application?")},
        text={ Text(fields[0]+" • "+fields[1]+"\n"+fields[3]+" • "+fields[4]) },
        dismissButton={TextButton(onClick={confirm=false}) {Text("Edit")}},
        confirmButton={Button(onClick={
            if (!busy) {
                confirm=false; busy=true
                val input=CollegeApplication(fields[0].trim(),fields[1].trim(),fields[2].trim().ifEmpty { null },
                    fields[3].trim(),fields[4].trim(),fields[5].trim(),fields[6])
                fields[6]=""; fields[7]=""
                scope.launch {
                    try { api.submit(input); receipt=true }
                    catch (e: CancellationException) { throw e }
                    catch (failure: Exception) { error = registrationSubmissionError(failure) }
                    finally { busy=false }
                }
            }
        }) {Text("Submit application")}})
}

package com.campussphere.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import android.os.Bundle
import org.junit.After

import androidx.test.platform.app.InstrumentationRegistry
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.auth.*
import com.campussphere.app.presentation.auth.viewmodel.*
import com.campussphere.app.presentation.platform.PlatformScreen
import com.campussphere.app.ui.theme.CampusSphereTheme

import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class CollegeApplicationVisualTest {
        private val inst=InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario:ActivityScenario<ComponentActivity>
    @After fun close() { if(::scenario.isInitialized) scenario.close() }
    private fun nodes(root:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> {
        if(root==null) return emptyList()
        return listOf(root)+(0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }
    private fun all()=nodes(inst.uiAutomation.rootInActiveWindow)
    private fun waitNode(match:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
        repeat(60) { all().firstOrNull(match)?.let { return it }; Thread.sleep(100) }
        error("Expected screen control did not appear")
    }
    private fun reveal(label:String):AccessibilityNodeInfo {
        repeat(12) {
            all().firstOrNull { it.text?.toString()==label }?.let { node ->
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                return node
            }
            val container = all().firstOrNull { it.isScrollable && !it.isEditable }
            if (container?.performAction(ACTION_SCROLL_FORWARD) != true) scrollDown()
            Thread.sleep(180)
        }
        error("Expected control missing: "+label)
    }
    private fun shell(command:String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun scrollTop() {
        repeat(3) { shell("input swipe 500 550 500 1600 260") }
    }
    private fun scrollDown() {
        val b=android.graphics.Rect()
        inst.uiAutomation.rootInActiveWindow?.getBoundsInScreen(b)
        shell("input swipe "+b.centerX()+" "+(b.height()*0.80).toInt()+" "+b.centerX()+" "+(b.height()*0.32).toInt()+" 350")
    }
    private fun tap(start:AccessibilityNodeInfo) {
        var node:AccessibilityNodeInfo?=start
        while(node!=null) {
            if(node.isClickable && node.performAction(ACTION_CLICK)) { Thread.sleep(250); return }
            node=node.parent
        }
        error("Control did not accept click")
    }
    private fun input(label:String,value:String) {
        val node=reveal(label)
        val edit=if(node.isEditable) node else nodes(node.parent).first { it.isEditable }
        check(edit.performAction(ACTION_SET_TEXT, Bundle().apply { putCharSequence(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }))
        Thread.sleep(100)
    }
    val sample=RegistrationDetails(1,"Example Institute","EXAMPLE","https://example.invalid","Example Applicant",
        "applicant@example.invalid","Registrar","PENDING","2026-12-23")
    private var submissionCount=0
    private var failSubmission=false
    private var conflict:String?=null
    val api=object:CollegeRegistrationApi {
        var pending=true
        override suspend fun submit(application:CollegeApplication) { submissionCount++; if(failSubmission) throw java.io.IOException("offline fixture") }
        override suspend fun pending(auth:String,after:Long)=RequestPage(if(pending) 1 else 0,if(pending) listOf(sample) else emptyList())
        override suspend fun detail(auth:String,id:Long)=sample
        override suspend fun approve(auth:String,id:Long,evidence:Verification) { check(evidence.mailbox); pending=false }
        override suspend fun reject(auth:String,id:Long,rejection:Rejection) {
            check(rejection.reason.isNotBlank())
            conflict?.let { code ->
                val body=okhttp3.ResponseBody.create(null,"{\"code\":\""+code+"\"}")
                throw retrofit2.HttpException(retrofit2.Response.error<Unit>(409,body))
            }
            pending=false
        }
    }
    private fun show(content:@Composable ()->Unit) {
        scenario=ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity -> activity.setContent { CampusSphereTheme { Surface { Column(Modifier.safeDrawingPadding()) {
            Text("Visual test • synthetic data"); content()
        } } } } }
        Thread.sleep(800)
    }
    private fun shot(name:String) {
        inst.waitForIdleSync(); Thread.sleep(400)
        val inst=InstrumentationRegistry.getInstrumentation()
        val dir=File(inst.targetContext.filesDir,"phase8-visual").apply {mkdirs()}
        File(dir,name+".png").outputStream().use {
            check(inst.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
        }
    }
    private fun click(text:String) { tap(reveal(text)) }
    @Test fun login() {
        show { LoginScreen(false,{_,_,_->},{},{}) }
        reveal("Register Your College")
        shot("01-login")
    }
    private fun fillAndSubmit() {
        show { CollegeApplicationScreen(api) {} }
        shot("02-registration")
        val fields=listOf("College name","Proposed college code","Official website (optional)","Administrator full name","Institutional email","Position at college","Password","Confirm password")
        val values=listOf("Example Institute","EXAMPLE","","Example Applicant","applicant@example.invalid",
            "Registrar","dummy-fixture-only","dummy-fixture-only")
        values.forEachIndexed { i,v -> if(v.isNotEmpty()) input(fields[i],v) }
        click("Review submission")
        waitNode { it.text?.toString()=="Submit college application?" }
        click("Submit application")
    }
    @Test fun failedSubmissionClearsPasswordsAndAllowsCorrection() {
        failSubmission=true
        fillAndSubmit()
        // Wait for the failed operation before interacting with the form again.
        val failure = "Submission could not be confirmed. Both password fields were cleared. Check your connection and re-enter your password to try again."
        reveal(failure)
        waitNode { it.text?.toString() == failure }
        shot("09-submission-error")
        val emptyPasswords = all().filter { it.isEditable && it.isPassword }
        check(emptyPasswords.size == 2 && emptyPasswords.all {
            it.text.isNullOrEmpty() || it.text.toString() in setOf("Password", "Confirm password")
        }) { "Both password fields must be empty after submission failure" }

        click("Review submission")
        waitNode { it.text?.toString() == "Check password length and confirmation." }
        check(submissionCount == 1)
        shot("10-password-validation")

        failSubmission=false
        input("Password","replacement-fixture")
        input("Confirm password","replacement-fixture")
        click("Review submission")
        waitNode { it.text?.toString()=="Submit college application?" }
        click("Submit application")
        waitNode { it.text?.toString()=="Submission received" }
        check(submissionCount == 2)
    }
    @Test fun application() {
        fillAndSubmit()
        waitNode { it.text?.toString()=="Submission received" }
        shot("03-receipt")
    }
    private fun review() {
        val platform=Proxy.newProxyInstance(PlatformApi::class.java.classLoader,arrayOf(PlatformApi::class.java)) { _,method,_ ->
            if(method.name=="overview") PlatformOverview(0,0,0,0) else error("Unexpected platform call")
        } as PlatformApi
        show { PlatformScreen(PlatformRepository(platform),{"fixture"},{},{},api) }
        click("Pending College Requests")
        waitNode { it.text?.toString()=="Example Institute • EXAMPLE" }
        shot("04-queue")
        click("Example Institute • EXAMPLE")
        Thread.sleep(1000)
        waitNode { it.text?.toString()=="Institutional verification" }
        shot("05-review")
    }
    @Test fun approve() {
        review()

        click("Official callback")
        click("Institution is genuine")
        click("Applicant submitted this request and is authorized")
        click("Applicant controls the institutional email")
        scrollDown(); Thread.sleep(400); click("Approve application")
        waitNode { it.text?.toString()=="Approve application?" }
        shot("06-approve")
        click("Confirm approve")
        waitNode { it.text?.toString()=="Application approved." }
    }
    private fun confirmRejection() {
        review()
        scrollDown(); Thread.sleep(400); input("Reason for rejection","Institution could not be confirmed")
        click("Reject application")
        waitNode { it.text?.toString()=="Reject application?" }
        shot("07-reject")
        click("Confirm reject")
    }
    @Test fun reject() {
        confirmRejection()
        waitNode { it.text?.toString()=="Application rejected." }
    }
    @Test fun expiredDecisionShowsConflict() {
        conflict="REGISTRATION_EXPIRED"
        confirmRejection()
        scrollTop(); reveal("This application expired. Return to the pending queue.")
    }
    @Test fun competingDecisionShowsConflict() {
        conflict="REGISTRATION_CONFLICT"
        confirmRejection()
        scrollTop(); reveal("Application already decided or account conflict. Return to the queue and review.")
    }
    @Test fun collegeAdmin() {
        show { SessionScreen(AuthUiState(AuthRoute.ACCOUNT,account=AuthorizedAccount(true,null,DashboardRole.COLLEGE_ADMIN,
            "No departments have been added to your college yet.")),{},{},{}) }
        shot("08-college-admin")
    }
}

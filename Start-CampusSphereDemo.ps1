param(
    [switch]$SkipBuild,
    [ValidateSet('StudentEnrollment', 'FacultyOnboarding', 'FacultyManagement', 'DivisionsSubjects', 'AcademicPeriods')]
    [string]$Phase = 'StudentEnrollment'
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$sdk = 'D:\OLD Pankti\Android\SDK'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$serial = 'emulator-5554'

if (-not $SkipBuild) {
    & .\gradlew.bat --offline :app:assembleDebug :app:assembleDebugAndroidTest
    if ($LASTEXITCODE) { throw 'Debug APK build failed.' }
}

$devices = @(& $adb devices)
if ($devices -match '^emulator-\d+\s+' -and -not ($devices -match "^$serial\s+")) {
    throw 'The connected emulator is not the expected Pixel 6.'
}
if (-not ($devices -match "^$serial\s+") -and
    -not (Get-Process emulator,qemu-system-x86_64 -ErrorAction SilentlyContinue)) {
    if ('Pixel_6' -notin @(& $emulator -list-avds | ForEach-Object { $_.Trim() })) {
        throw 'The existing Pixel_6 AVD could not be found.'
    }
    Start-Process -FilePath $emulator -ArgumentList '-avd Pixel_6 -no-snapshot -gpu software -port 5554'
}
$deadline = (Get-Date).AddMinutes(4)
$boot = @()
do {
    # A cold boot can take several seconds to register with ADB.
    if (@(& $adb devices) -match "^$serial\s+device\s*$") {
        try {
            $boot = @(& $adb -s $serial shell getprop sys.boot_completed 2>$null) |
                ForEach-Object { $_.Trim() }
        } catch {
            $boot = @()
        }
        if ('1' -in $boot) { break }
    }
    Start-Sleep 3
} while ((Get-Date) -lt $deadline)
if ('1' -notin $boot -or 'Pixel_6' -notin @(& $adb -s $serial emu avd name | ForEach-Object { $_.Trim() })) {
    throw 'Pixel 6 identity or boot state could not be verified.'
}
foreach ($apk in 'app/build/outputs/apk/debug/app-debug.apk',
    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') {
    & $adb -s $serial install -r $apk
    if ($LASTEXITCODE) { throw 'Debug package installation failed.' }
}
& $adb -s $serial shell input keyevent KEYCODE_WAKEUP
& $adb -s $serial shell wm dismiss-keyguard
$demo = @{
    FacultyOnboarding = @{
        Description = 'Faculty Onboarding'
        Test = 'facultyOnboardingWalkthrough'
        DeviceFolder = 'faculty-onboarding-demo'
        ReportFolder = 'faculty-onboarding-demo-pixel6'
        ScreenshotCount = 5
    }
    StudentEnrollment = @{
        Description = 'Student Management and Enrollment'
        Test = 'studentEnrollmentWalkthrough'
        DeviceFolder = 'student-enrollment-polished-demo'
        ReportFolder = 'student-enrollment-polished-pixel6'
        ScreenshotCount = 27
    }
    FacultyManagement = @{
        Description = 'Faculty Management and Subject Assignment'
        Test = 'facultyManagementWalkthrough'
        DeviceFolder = 'faculty-management-demo'
        ReportFolder = 'faculty-management-demo-pixel6'
        ScreenshotCount = 21
    }
    DivisionsSubjects = @{
        Description = 'Divisions and Subjects'
        Test = 'divisionsAndSubjectsWalkthrough'
        DeviceFolder = 'division-subject-demo'
        ReportFolder = 'divisions-subjects-demo-pixel6'
        ScreenshotCount = 26
    }
    AcademicPeriods = @{
        Description = 'Academic Years and Semesters'
        Test = 'academicYearsAndSemestersWalkthrough'
        DeviceFolder = 'academic-period-demo'
        ReportFolder = 'academic-periods-demo-pixel6'
        ScreenshotCount = 22
    }
}[$Phase]
Write-Host "Running the $($demo.Description) demonstration with synthetic data."
$test = "com.campussphere.app.DepartmentVisualTest#$($demo.Test)"
$runner = 'com.campussphere.app.debug.test/androidx.test.runner.AndroidJUnitRunner'
$output = & $adb -s $serial shell am instrument -w -e class $test $runner
$exitCode = $LASTEXITCODE
$output
if ($exitCode -ne 0 -or -not ($output -match '^\s*OK \(1 test\)')) {
    throw 'The Pixel 6 demonstration did not pass.'
}

$destination = Join-Path $PSScriptRoot "app\build\reports\$($demo.ReportFolder)"
[IO.Directory]::CreateDirectory($destination) | Out-Null
$folder = "files/$($demo.DeviceFolder)"
$images = @(& $adb -s $serial shell run-as com.campussphere.app.debug ls $folder |
    ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^\d\d-[a-z-]+\.png$' })
if ($images.Count -ne $demo.ScreenshotCount) { throw 'The screenshot count does not match the selected demonstration.' }
foreach ($image in $images) {
    $info = [Diagnostics.ProcessStartInfo]::new($adb,
        "-s $serial exec-out run-as com.campussphere.app.debug cat $folder/$image")
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $pull = [Diagnostics.Process]::Start($info)
    try {
        $data = [IO.File]::Create((Join-Path $destination $image))
        try { $pull.StandardOutput.BaseStream.CopyTo($data) } finally { $data.Dispose() }
        $pull.WaitForExit()
        if ($pull.ExitCode) { throw "Could not export $image" }
    } finally { $pull.Dispose() }
}
Write-Host "Screenshots: $destination"

param([switch]$Describe)

$ErrorActionPreference = 'Stop'
$backend = 'D:\OLD Pankti\CampusSphereBackend\campus-sphere-backend'
$java = 'D:\Development\Tools\JDK21\bin\java.exe'
$identitySql = "SELECT 1 / CASE WHEN current_database()='campus_sphere_phase9_rehearsal' AND inet_server_addr()='127.0.0.1'::inet AND inet_server_port()=5432 AND current_setting('server_version_num')='190000' AND current_user='postgres' AND session_user='postgres' AND current_schema()='public' THEN 1 ELSE 0 END"
$springArgs = @(
    '--spring.config.location=classpath:/application.properties'
    '--server.address=127.0.0.1'
    '--server.port=8080'
    '--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/campus_sphere_phase9_rehearsal'
    '--spring.datasource.username=postgres'
    "--spring.datasource.hikari.connection-init-sql=$identitySql"
    '--spring.flyway.enabled=false'
    '--spring.jpa.hibernate.ddl-auto=validate'
    '--spring.jpa.show-sql=false'
    '--spring.sql.init.mode=never'
    '--app.registration.expiry-enabled=false'
)
if ($Describe) {
    $springArgs
    return
}

$reportPath = Join-Path $backend 'target\surefire-reports\TEST-com.campussphere.backend.operator.Phase9RehearsalRunnerTest.xml'
$classes = Join-Path $backend 'target\classes'
if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $classes 'com\campussphere\backend\CampusSphereBackendApplication.class') -PathType Leaf)) {
    throw 'Local Java or compiled backend is missing. Compile offline first.'
}
$report = [xml](Get-Content -Raw -LiteralPath $reportPath)
$entries = @(($report.testsuite.properties.property | Where-Object name -eq 'java.class.path').value -split ';' |
    Where-Object { $_ -and $_ -notmatch '[\\/]target[\\/]test-classes[\\/]?$' })
if ($entries.Count -eq 0 -or $classes -notin $entries) {
    throw 'Offline Surefire classpath is missing the backend main classes.'
}
$classpath = $entries -join ';'

$confirm = Read-Host 'Type START campus_sphere_phase9_rehearsal to confirm the isolated backend launch'
if ($confirm -cne 'START campus_sphere_phase9_rehearsal') { throw 'Launch cancelled.' }
$secret = Read-Host 'Local PostgreSQL password' -AsSecureString
if ($secret.Length -eq 0) { throw 'Password input missing.' }
$jwtBytes = New-Object byte[] 48
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$handle = [IntPtr]::Zero
$argfile = $null
try {
    $rng.GetBytes($jwtBytes)
    $argfile = [IO.Path]::GetTempFileName()
    $quotedClasspath = '"' + $classpath.Replace('\','/') + '"'
    $launchArgs = @('-Dspring.devtools.restart.enabled=false', '-cp', $quotedClasspath, 'com.campussphere.backend.CampusSphereBackendApplication')
    $launchArgs += @($springArgs | ForEach-Object { '"' + $_ + '"' })
    [IO.File]::WriteAllLines($argfile, [string[]]$launchArgs, [Text.UTF8Encoding]::new($false))

    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $java
    $start.Arguments = '@"' + $argfile + '"'
    $start.WorkingDirectory = $backend
    $start.UseShellExecute = $false
    foreach ($name in @($start.EnvironmentVariables.Keys)) {
        if ($name -match '^(SPRING_|DB_|APP_|PG|SERVER_|LOGGING_)' -or
            $name -in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JWT_SECRET')) {
            $start.EnvironmentVariables.Remove($name)
        }
    }
    $handle = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try {
        $start.EnvironmentVariables['DB_PASSWORD'] = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($handle)
        $start.EnvironmentVariables['JWT_SECRET'] = [Convert]::ToBase64String($jwtBytes)
        $process = [Diagnostics.Process]::Start($start)
    } finally {
        $start.EnvironmentVariables.Remove('DB_PASSWORD')
        $start.EnvironmentVariables.Remove('JWT_SECRET')
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($handle)
        $handle = [IntPtr]::Zero
    }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Backend stopped with exit code $($process.ExitCode)." }
} finally {
    if ($handle -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($handle) }
    $secret.Dispose()
    $rng.Dispose()
    [Array]::Clear($jwtBytes, 0, $jwtBytes.Length)
    if ($argfile) { Remove-Item -LiteralPath $argfile -ErrorAction SilentlyContinue }
}

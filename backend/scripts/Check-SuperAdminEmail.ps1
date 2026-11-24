$ErrorActionPreference = 'Stop'

$java = 'D:\Development\Tools\JDK21\bin\java.exe'
$validator = Join-Path $PSScriptRoot '..\src\main\java\com\campussphere\backend\operator\ProvisioningEmail.java'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $validator)) {
    throw 'The local Java 21 executable or provisioning email validator is missing.'
}

$previousEncoding = $OutputEncoding
try {
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    while ($true) {
        $entry = Read-Host 'Intended Super Admin email (hidden, Enter to cancel)' -AsSecureString
        if ($null -eq $entry) {
            Write-Host 'Email check cancelled.'
            return
        }

        try {
            $inputText = [Net.NetworkCredential]::new('', $entry).Password
            $result = $inputText | & $java $validator
            if ($LASTEXITCODE -ne 0) { throw 'The Java email validator failed.' }
        } finally {
            $inputText = $null
            $entry.Dispose()
        }

        switch -CaseSensitive ($result) {
            'BLANK' {
                Write-Host 'No email was entered. Check cancelled.'
                return
            }
            'MALFORMED' {
                Write-Host 'Email format invalid. Enter the address alone, for example admin@example.com.'
            }
            'VALID' {
                Write-Host 'Email format valid. Database availability was not checked.'
                return
            }
            default { throw 'Unexpected response from the Java email validator.' }
        }
    }
} finally {
    $OutputEncoding = $previousEncoding
}

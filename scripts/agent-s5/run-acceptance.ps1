param([switch]$Live, [switch]$Browser)
$ErrorActionPreference = 'Stop'
$taskUrl = $env:PAMS_S5_DB_URL
if (!$taskUrl -or $taskUrl -notmatch '^jdbc:mysql://127\.0\.0\.1:[0-9]+/pams_s5_[a-zA-Z0-9_]+\?.*$') {
    throw 'PAMS_S5_DB_URL must point to an isolated pams_s5_* database on a loopback port.'
}
if (!$env:PAMS_S5_DB_USER -or !$env:PAMS_S5_DB_PASSWORD) { throw 'S5 database credentials are required.' }
if ($Browser -and !$Live) { throw 'The real browser flow requires -Live.' }
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$taskNames = @('PAMS_S5_ACCEPTANCE','PAMS_S5_LIVE','PAMS_S5_BROWSER','PAMS_TEST_AGENT_PROVIDER')
$taskPrevious = @{}
foreach ($taskName in $taskNames) { $taskPrevious[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process') }
try {
    $env:PAMS_S5_ACCEPTANCE = 'true'
    $env:PAMS_S5_LIVE = if ($Live) { 'true' } else { 'false' }
    $env:PAMS_S5_BROWSER = if ($Browser) { 'true' } else { 'false' }
    $env:PAMS_TEST_AGENT_PROVIDER = if ($Live) { 'deepseek' } else { 'stub' }
    Push-Location $taskRepo
    try {
        & 'C:\dev\Git\bin\bash.exe' -c 'mvn -q -pl pams-backend -am -Dtest=S5AcceptanceTest,S5ProcessRecoveryTest,S5BrowserIntegrationTest -Dsurefire.runOrder=alphabetical -Dsurefire.failIfNoSpecifiedTests=false test'
        if ($LASTEXITCODE -ne 0) { throw 'S5 acceptance failed. Inspect Surefire and docs/audits/lingsi-upgrade-implementation/s5-*.' }
    } finally { Pop-Location }
} finally {
    foreach ($taskName in $taskNames) { [Environment]::SetEnvironmentVariable($taskName, $taskPrevious[$taskName], 'Process') }
}

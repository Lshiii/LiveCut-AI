$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$launcher = Join-Path $repoRoot 'scripts/worker.ps1'
$testDirectory = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('livecut-worker-launch-test-' + [Guid]::NewGuid().ToString('N'))))
$configPath = Join-Path $testDirectory 'worker.env'
$variableName = 'LIVECUT_WORKER_LAUNCH_TEST'
$originalValue = [Environment]::GetEnvironmentVariable($variableName, 'Process')
$originalCulture = [Globalization.CultureInfo]::CurrentCulture
$workerLaunchTestState = @{ LaunchCount = 0; Arguments = @() }

# The launcher resolves this function before the external uv command. No worker
# process, Java service, model API, or real configuration is used by this test.
function uv {
    $workerLaunchTestState.LaunchCount += 1
    $workerLaunchTestState.Arguments = @($args)
    $global:LASTEXITCODE = 0
}

try {
    New-Item -ItemType Directory -Path $testDirectory | Out-Null
    [IO.File]::WriteAllText($configPath, "$variableName=fixture-loaded`n", [Text.UTF8Encoding]::new($false))
    [Environment]::SetEnvironmentVariable($variableName, 'before-launch', 'Process')
    [Globalization.CultureInfo]::CurrentCulture = [Globalization.CultureInfo]::GetCultureInfo('fr-FR')
    foreach ($case in @(@{ Value = $null; Expected = '45' }, @{ Value = 0; Expected = '0' },
                        @{ Value = 1.25; Expected = '1.25' }, @{ Value = 3600; Expected = '3600' })) {
        $parameters = @{ EnvFile = $configPath; WorkerId = 'launch-test'; Once = $true; StageType = 'MEDIA' }
        if ($null -ne $case.Value) { $parameters.RegistrationWaitSeconds = $case.Value }
        & $launcher @parameters
        $index = [Array]::IndexOf($workerLaunchTestState.Arguments, '--registration-wait-seconds')
        if ($index -lt 0 -or $workerLaunchTestState.Arguments[$index + 1] -cne $case.Expected) {
            throw 'Registration wait did not reach the CLI using invariant numeric formatting.'
        }
        if ($workerLaunchTestState.Arguments -notcontains '--once' -or $workerLaunchTestState.Arguments -notcontains 'MEDIA') {
            throw 'Existing one-shot stage arguments were not preserved.'
        }
        if ([Environment]::GetEnvironmentVariable($variableName, 'Process') -cne 'before-launch') {
            throw 'Launcher did not restore the fixture environment.'
        }
    }
    $launchCountBeforeInvalid = $workerLaunchTestState.LaunchCount
    foreach ($value in @(-1, 3600.01, [double]::NaN, [double]::PositiveInfinity, [double]::NegativeInfinity)) {
        $rejected = $false
        try { & $launcher -EnvFile $configPath -RegistrationWaitSeconds $value } catch { $rejected = $true }
        if (-not $rejected -or $workerLaunchTestState.LaunchCount -ne $launchCountBeforeInvalid) {
            throw 'Invalid registration wait reached the external command.'
        }
        if ([Environment]::GetEnvironmentVariable($variableName, 'Process') -cne 'before-launch') {
            throw 'Invalid registration wait changed the process environment.'
        }
    }
    # Parameter binding rejects invalid waits before attempting to read a config.
    $rejected = $false
    try { & $launcher -EnvFile (Join-Path $testDirectory 'missing.env') -RegistrationWaitSeconds ([double]::NaN) }
    catch { $rejected = $_.FullyQualifiedErrorId -like 'ParameterArgumentValidationError*' }
    if (-not $rejected) { throw 'Invalid registration wait was not rejected during parameter binding.' }
    [pscustomobject]@{ status = 'passed'; launches = $workerLaunchTestState.LaunchCount; invalid_waits_rejected = 6 } | ConvertTo-Json -Compress
} finally {
    [Globalization.CultureInfo]::CurrentCulture = $originalCulture
    [Environment]::SetEnvironmentVariable($variableName, $originalValue, 'Process')
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $actualParent = [IO.Path]::GetDirectoryName($testDirectory)
    if ($actualParent -cne $expectedParent -or [IO.Path]::GetFileName($testDirectory) -notlike 'livecut-worker-launch-test-*') {
        throw 'Refusing to remove a directory outside the launcher test workspace.'
    }
    if (Test-Path -LiteralPath $testDirectory) { Remove-Item -LiteralPath $testDirectory -Recurse -Force }
}

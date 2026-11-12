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
    if ([Environment]::GetEnvironmentVariable($variableName, 'Process') -cne 'fixture-loaded') {
        throw 'The mock command did not receive the isolated fixture environment.'
    }
    $workerLaunchTestState.LaunchCount += 1
    $workerLaunchTestState.Arguments = @($args)
    $global:LASTEXITCODE = 0
}
function Assert-WorkerFlag {
    param([string]$Flag, [string]$Expected)
    $index = [Array]::IndexOf($workerLaunchTestState.Arguments, $Flag)
    $occurrences = @($workerLaunchTestState.Arguments | Where-Object { $_ -ceq $Flag })
    if ($occurrences.Count -ne 1 -or $index -lt 0 -or $index + 1 -ge $workerLaunchTestState.Arguments.Count -or
        $workerLaunchTestState.Arguments[$index + 1] -cne $Expected) {
        throw "Worker flag $Flag did not reach the CLI once with its expected value."
    }
}

try {
    New-Item -ItemType Directory -Path $testDirectory | Out-Null
    [IO.File]::WriteAllText($configPath, "$variableName=fixture-loaded`n", [Text.UTF8Encoding]::new($false))
    [Environment]::SetEnvironmentVariable($variableName, 'before-launch', 'Process')
    [Globalization.CultureInfo]::CurrentCulture = [Globalization.CultureInfo]::GetCultureInfo('fr-FR')
    $cases = @(
        @{ Registration = $null; Reconnect = $null; ExpectedRegistration = '45'; ExpectedReconnect = '180' },
        @{ Registration = 0; Reconnect = $null; ExpectedRegistration = '0'; ExpectedReconnect = '180' },
        @{ Registration = 1.25; Reconnect = $null; ExpectedRegistration = '1.25'; ExpectedReconnect = '180' },
        @{ Registration = 3600; Reconnect = $null; ExpectedRegistration = '3600'; ExpectedReconnect = '180' },
        @{ Registration = $null; Reconnect = 0; ExpectedRegistration = '45'; ExpectedReconnect = '0' },
        @{ Registration = $null; Reconnect = 1.25; ExpectedRegistration = '45'; ExpectedReconnect = '1.25' },
        @{ Registration = $null; Reconnect = 3600; ExpectedRegistration = '45'; ExpectedReconnect = '3600' },
        @{ Registration = $null; Reconnect = 0.0001; ExpectedRegistration = '45'; ExpectedReconnect = '0.0001' },
        @{ Registration = 0; Reconnect = 3600; ExpectedRegistration = '0'; ExpectedReconnect = '3600' },
        @{ Registration = 3600; Reconnect = 0; ExpectedRegistration = '3600'; ExpectedReconnect = '0' },
        @{ Registration = 2.5; Reconnect = 0.125; ExpectedRegistration = '2.5'; ExpectedReconnect = '0.125' },
        @{ Registration = 0.125; Reconnect = 2.5; ExpectedRegistration = '0.125'; ExpectedReconnect = '2.5' }
    )
    foreach ($case in $cases) {
        $parameters = @{ EnvFile = $configPath; WorkerId = 'launch-test'; Once = $true; StageType = 'MEDIA' }
        if ($null -ne $case.Registration) { $parameters.RegistrationWaitSeconds = $case.Registration }
        if ($null -ne $case.Reconnect) { $parameters.ReconnectWaitSeconds = $case.Reconnect }
        & $launcher @parameters
        Assert-WorkerFlag '--registration-wait-seconds' $case.ExpectedRegistration
        Assert-WorkerFlag '--reconnect-wait-seconds' $case.ExpectedReconnect
        if ($workerLaunchTestState.Arguments -notcontains '--once' -or $workerLaunchTestState.Arguments -notcontains 'MEDIA') {
            throw 'Existing one-shot stage arguments were not preserved.'
        }
        if ([Environment]::GetEnvironmentVariable($variableName, 'Process') -cne 'before-launch') {
            throw 'Launcher did not restore the fixture environment.'
        }
    }
    $stageCases = @('MEDIA', 'ASR', 'SEGMENT', 'TIMELINE', 'EXPORT_CLIP', 'CAPTION_PROOFREAD', 'ANCHOR_CLIP', 'AGENT_VISUAL', 'AGENT_VISUAL_FOCUS', 'AGENT_EVIDENCE')
    $stageJob = '11111111-1111-4111-8111-111111111111'
    foreach ($stage in $stageCases) {
        & $launcher -EnvFile $configPath -JavaUrl 'http://127.0.0.1:18083' -WorkerId 'stage-launch-test' -JobId $stageJob -StageType $stage -Once
        Assert-WorkerFlag '--stage-type' $stage
        Assert-WorkerFlag '--job-id' $stageJob
        Assert-WorkerFlag '--java-url' 'http://127.0.0.1:18083'
        Assert-WorkerFlag '--worker-id' 'stage-launch-test'
        if (@($workerLaunchTestState.Arguments | Where-Object { $_ -ceq '--once' }).Count -ne 1) {
            throw 'The one-shot stage selection did not reach the mock CLI exactly once.'
        }
        if ([Environment]::GetEnvironmentVariable($variableName, 'Process') -cne 'before-launch') {
            throw 'Stage launch did not restore the fixture environment.'
        }
    }
    foreach ($replayStage in @('', 'AGENT_EVIDENCE')) {
        & $launcher -EnvFile $configPath -WorkerId 'replay-launch-test' -JobId $stageJob -StageType $replayStage -CachedAnswerReplayOnly -Once
        if (@($workerLaunchTestState.Arguments | Where-Object { $_ -ceq '--cached-answer-replay-only' }).Count -ne 1) {
            throw 'Replay-only mode did not reach the CLI exactly once.'
        }
        Assert-WorkerFlag '--job-id' $stageJob
        if ([Environment]::GetEnvironmentVariable($variableName, 'Process') -cne 'before-launch') {
            throw 'Replay launch did not restore the fixture environment.'
        }
    }
    $launchCountBeforeInvalid = $workerLaunchTestState.LaunchCount
    foreach ($badReplay in @(@{ StageType = 'ASR' }, @{ StageType = 'MEDIA' }, @{ ReuseAsrJob = $stageJob })) {
        $rejectedReplay = $false
        try { & $launcher -EnvFile $configPath -CachedAnswerReplayOnly @badReplay }
        catch { $rejectedReplay = $true }
        if (-not $rejectedReplay -or $workerLaunchTestState.LaunchCount -ne $launchCountBeforeInvalid) {
            throw 'Conflicting replay-only arguments were not rejected before launch.'
        }
    }
    $invalidWaits = 0
    foreach ($parameterName in @('RegistrationWaitSeconds', 'ReconnectWaitSeconds')) {
        foreach ($value in @(-1, 3600.01, [double]::NaN, [double]::PositiveInfinity, [double]::NegativeInfinity, 'not-a-number')) {
            foreach ($path in @($configPath, (Join-Path $testDirectory 'missing.env'))) {
                $parameters = @{ EnvFile = $path }
                $parameters[$parameterName] = $value
                $rejected = $false
                try { & $launcher @parameters }
                catch { $rejected = $_.FullyQualifiedErrorId -like 'ParameterArgumentValidationError*' -or $_.FullyQualifiedErrorId -like 'ParameterArgumentTransformationError*' }
                if (-not $rejected -or $workerLaunchTestState.LaunchCount -ne $launchCountBeforeInvalid) {
                    throw "Invalid $parameterName was not rejected before config access or external launch."
                }
                if ([Environment]::GetEnvironmentVariable($variableName, 'Process') -cne 'before-launch') {
                    throw "Invalid $parameterName changed the process environment."
                }
                $invalidWaits++
            }
        }
    }
    $invalidStages = 0
    foreach ($stage in @('UNKNOWN_STAGE', 'ANCHOR_CLIPS')) {
        foreach ($path in @($configPath, (Join-Path $testDirectory 'missing.env'))) {
            $rejected = $false
            try { & $launcher -EnvFile $path -StageType $stage }
            catch { $rejected = $_.FullyQualifiedErrorId -like 'ParameterArgumentValidationError*' }
            if (-not $rejected -or $workerLaunchTestState.LaunchCount -ne $launchCountBeforeInvalid) {
                throw 'An unknown stage was not rejected before config access or external launch.'
            }
            if ([Environment]::GetEnvironmentVariable($variableName, 'Process') -cne 'before-launch') {
                throw 'An invalid stage changed the process environment.'
            }
            $invalidStages++
        }
    }
    & $launcher -EnvFile $configPath -WorkerId 'kafka-launch-test' -StageType MEDIA -DispatchMode kafka -KafkaBootstrapServers '127.0.0.1:19092' -ReconcileSeconds 60.5
    Assert-WorkerFlag '--dispatch-mode' 'kafka'
    Assert-WorkerFlag '--kafka-bootstrap-servers' '127.0.0.1:19092'
    Assert-WorkerFlag '--reconcile-seconds' '60.5'
    if ($workerLaunchTestState.Arguments -notcontains 'kafka') { throw 'Kafka extra was not requested.' }
    $beforeRejectedKafka = $workerLaunchTestState.LaunchCount
    $kafkaOnceRejected = $false
    try { & $launcher -EnvFile $configPath -WorkerId 'kafka-launch-test' -DispatchMode kafka -Once }
    catch { $kafkaOnceRejected = $true }
    if (-not $kafkaOnceRejected -or $workerLaunchTestState.LaunchCount -ne $beforeRejectedKafka) { throw 'One-shot Kafka must fail before launch.' }
    if ([Environment]::GetEnvironmentVariable($variableName, 'Process') -cne 'before-launch') { throw 'Rejected Kafka launch changed the environment.' }
    $dispatchBeforeKafkaTest = [Environment]::GetEnvironmentVariable('LIVECUT_DISPATCH_MODE', 'Process')
    $reconcileBeforeKafkaTest = [Environment]::GetEnvironmentVariable('LIVECUT_KAFKA_RECONCILE_SECONDS', 'Process')
    try {
        $env:LIVECUT_DISPATCH_MODE = 'kafka'
        $env:LIVECUT_KAFKA_RECONCILE_SECONDS = '137'
        & $launcher -EnvFile $configPath -WorkerId 'explicit-poll-test' -DispatchMode poll -Once
        Assert-WorkerFlag '--dispatch-mode' 'poll'
        if ($workerLaunchTestState.Arguments -contains 'kafka') { throw 'Explicit poll installed Kafka or inherited the Kafka mode.' }
        & $launcher -EnvFile $configPath -WorkerId 'inherited-kafka-test'
        Assert-WorkerFlag '--dispatch-mode' 'kafka'
        if ($workerLaunchTestState.Arguments -contains '--reconcile-seconds') { throw 'Unspecified reconcile interval overrode the configured environment.' }
    } finally {
        [Environment]::SetEnvironmentVariable('LIVECUT_DISPATCH_MODE', $dispatchBeforeKafkaTest, 'Process')
        [Environment]::SetEnvironmentVariable('LIVECUT_KAFKA_RECONCILE_SECONDS', $reconcileBeforeKafkaTest, 'Process')
    }
    [pscustomobject]@{ status = 'passed'; launches = $workerLaunchTestState.LaunchCount; stages_forwarded = $stageCases.Count;
        invalid_stages_rejected = $invalidStages; invalid_waits_rejected = $invalidWaits;
        registration_default = 45; reconnect_default = 180; culture = 'fr-FR' } | ConvertTo-Json -Compress
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

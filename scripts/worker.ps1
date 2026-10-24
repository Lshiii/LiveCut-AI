param(
    [string]$EnvFile = '.env.java',
    [string]$JavaUrl = 'http://127.0.0.1:8080',
    [string]$WorkerId = 'worker-local',
    [ValidateSet('', 'MEDIA', 'ASR', 'SEGMENT', 'TIMELINE')]
    [string]$StageType = '',
    [switch]$Once
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$configPath = if ([IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $repoRoot $EnvFile }
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { throw 'Missing private Worker configuration; see docs/09-Python计算Worker.md.' }
$original = @{}
try {
    foreach ($entry in [IO.File]::ReadAllLines($configPath)) {
        if ([string]::IsNullOrWhiteSpace($entry) -or $entry.TrimStart().StartsWith('#')) { continue }
        if ($entry -notmatch '^(LIVECUT_[A-Z0-9_]+)=(.*)$') { throw 'Only LIVECUT_ environment entries are accepted.' }
        $entryName, $entryValue = $Matches[1], $Matches[2]
        if ($original.ContainsKey($entryName)) { throw 'Duplicate environment variable.' }
        $original[$entryName] = [Environment]::GetEnvironmentVariable($entryName, 'Process')
        [Environment]::SetEnvironmentVariable($entryName, $entryValue, 'Process')
    }
    $storage = if ($env:LIVECUT_STORAGE_ROOT) { $env:LIVECUT_STORAGE_ROOT } else { '../storage' }
    if (-not [IO.Path]::IsPathRooted($storage)) { $storage = [IO.Path]::GetFullPath((Join-Path (Join-Path $repoRoot 'backend-java') $storage)) }
    $launchArgs = @('run', '--extra', 'ocr', 'livecut', 'worker', '--java-url', $JavaUrl,
        '--worker-id', $WorkerId, '--storage-root', $storage,
        '--journal-dir', (Join-Path $repoRoot "data/worker-journals/$WorkerId"))
    if ($WorkerId -notmatch '^[A-Za-z0-9_-]{1,100}$') { throw 'Invalid worker identifier.' }
    if ($StageType) { $launchArgs += @('--stage-type', $StageType) }
    if ($Once) { $launchArgs += '--once' }
    Push-Location $repoRoot
    try {
        & uv @launchArgs
        if ($LASTEXITCODE -ne 0) { throw "Worker exited with code $LASTEXITCODE." }
    } finally { Pop-Location }
} finally {
    foreach ($entryName in $original.Keys) { [Environment]::SetEnvironmentVariable($entryName, $original[$entryName], 'Process') }
}

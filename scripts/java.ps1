param(
    [ValidateSet('Verify', 'Test', 'Package', 'Run')]
    [string]$Task = 'Verify',
    [string]$EnvFile = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($EnvFile)) {
    $EnvFile = if ($Task -in @('Verify', 'Test')) { '.env.java-test' } else { '.env.java' }
}
$configPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $repoRoot $EnvFile }
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing private environment file. See docs/07-Java视频来源服务.md."
}

$original = @{}
try {
    foreach ($line in [System.IO.File]::ReadAllLines($configPath)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid environment entry; expected NAME=value.' }
        $name, $value = $Matches[1], $Matches[2]
        if (-not $name.StartsWith('LIVECUT_')) { throw 'Only LIVECUT_ variables are accepted.' }
        if ($original.ContainsKey($name)) { throw 'Duplicate environment variable.' }
        $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    Push-Location (Join-Path $repoRoot 'backend-java')
    try {
        $mavenArgs = switch ($Task) {
            'Verify' { @('--batch-mode', 'verify') }
            'Test' { @('--batch-mode', 'test') }
            'Package' { @('--batch-mode', '-DskipTests', 'package') }
            'Run' { @('--batch-mode', 'spring-boot:run') }
        }
        if ($IsWindows -or $env:OS -eq 'Windows_NT') {
            & .\mvnw.cmd @mavenArgs
        } else {
            & ./mvnw @mavenArgs
        }
        if ($LASTEXITCODE -ne 0) { throw "Maven exited with code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $original.Keys) {
        [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process')
    }
}

param(
    [string]$PackageDirectory = '',
    [string]$EnvFile = '',
    [ValidateRange(0, 65535)][int]$Port = 0,
    [switch]$DisableScanners
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$workbenchPortable = Test-Path -LiteralPath (Join-Path $PSScriptRoot 'package-manifest.json') -PathType Leaf
$workbenchBase = if ($workbenchPortable) { $PSScriptRoot } else { Split-Path -Parent $PSScriptRoot }
if ([string]::IsNullOrWhiteSpace($PackageDirectory)) {
    if (-not $workbenchPortable) { throw 'WORKBENCH_PACKAGE_REQUIRED: pass the directory produced by package-workbench.ps1.' }
    $PackageDirectory = $PSScriptRoot
}
$workbenchPackage = [IO.Path]::GetFullPath($(if ([IO.Path]::IsPathRooted($PackageDirectory)) { $PackageDirectory } else { Join-Path $workbenchBase $PackageDirectory }))
$workbenchJar = Join-Path $workbenchPackage 'livecut-workbench.jar'
$workbenchManifestFile = Join-Path $workbenchPackage 'package-manifest.json'
foreach ($path in @($workbenchPackage, $workbenchJar, $workbenchManifestFile)) {
    if (-not (Test-Path -LiteralPath $path) -or ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'WORKBENCH_PACKAGE_UNAVAILABLE' }
}
$workbenchManifest = [IO.File]::ReadAllText($workbenchManifestFile) | ConvertFrom-Json
if ($workbenchManifest.schema_version -ne 1 -or $workbenchManifest.kind -cne 'livecut-workbench-package' -or $workbenchManifest.public_context_path -cne '/' -or
    $workbenchManifest.artifact.filename -cne 'livecut-workbench.jar' -or $workbenchManifest.artifact.sha256 -cnotmatch '^[a-f0-9]{64}$' -or
    (Get-Item -LiteralPath $workbenchJar).Length -ne $workbenchManifest.artifact.bytes -or
    (Get-FileHash -LiteralPath $workbenchJar -Algorithm SHA256).Hash.ToLowerInvariant() -cne $workbenchManifest.artifact.sha256) { throw 'WORKBENCH_PACKAGE_HASH_MISMATCH' }

if ([string]::IsNullOrWhiteSpace($EnvFile)) { $EnvFile = '.env.java' }
$workbenchConfig = [IO.Path]::GetFullPath($(if ([IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $workbenchBase $EnvFile }))
if (-not (Test-Path -LiteralPath $workbenchConfig -PathType Leaf)) { throw 'WORKBENCH_CONFIG_MISSING: copy the configuration example and fill your database settings.' }
$workbenchValues = @{}
foreach ($line in [IO.File]::ReadAllLines($workbenchConfig)) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
    if ($line -notmatch '^(LIVECUT_[A-Z0-9_]+)=(.*)$' -or $workbenchValues.ContainsKey($Matches[1])) { throw 'WORKBENCH_CONFIG_INVALID' }
    $workbenchValues[$Matches[1]] = $Matches[2]
}
foreach ($name in @('LIVECUT_DB_URL', 'LIVECUT_DB_USER', 'LIVECUT_DB_PASSWORD')) {
    if (-not $workbenchValues.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($workbenchValues[$name])) { throw 'WORKBENCH_DATABASE_CONFIG_REQUIRED' }
}
$workbenchConfigBase = if ($workbenchPortable) { $workbenchPackage } else { Join-Path $workbenchBase 'backend-java' }
foreach ($name in @('LIVECUT_STORAGE_ROOT', 'LIVECUT_CAPTION_FONT_PATH')) {
    if ($workbenchValues.ContainsKey($name) -and -not [string]::IsNullOrWhiteSpace($workbenchValues[$name]) -and -not [IO.Path]::IsPathRooted($workbenchValues[$name])) {
        $workbenchValues[$name] = [IO.Path]::GetFullPath((Join-Path $workbenchConfigBase $workbenchValues[$name]))
    }
}
if (-not $workbenchValues.ContainsKey('LIVECUT_STORAGE_ROOT')) { $workbenchValues['LIVECUT_STORAGE_ROOT'] = [IO.Path]::GetFullPath((Join-Path $workbenchConfigBase $(if ($workbenchPortable) { 'storage' } else { '../storage' }))) }
if ($Port -eq 0) {
    $Port = 8080
    if ($workbenchValues.ContainsKey('LIVECUT_API_PORT') -and (-not [int]::TryParse($workbenchValues['LIVECUT_API_PORT'], [ref]$Port) -or $Port -lt 1 -or $Port -gt 65535)) { throw 'WORKBENCH_PORT_INVALID' }
}
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $javaHomeBinary = Join-Path $env:JAVA_HOME $(if ($env:OS -eq 'Windows_NT') { 'bin/java.exe' } else { 'bin/java' })
    $workbenchJava = Get-Command $javaHomeBinary -ErrorAction Stop
} else { $workbenchJava = Get-Command 'java' -ErrorAction Stop }
$workbenchSaved = @{}
try {
    foreach ($entry in @(Get-ChildItem Env: | Where-Object { $_.Name.StartsWith('LIVECUT_') -or $_.Name -in @('DASHSCOPE_API_KEY', 'DEEPSEEK_API_KEY', 'OPENAI_API_KEY') })) {
        $workbenchSaved[$entry.Name] = $entry.Value
        [Environment]::SetEnvironmentVariable($entry.Name, $null, 'Process')
    }
    foreach ($entry in $workbenchValues.GetEnumerator()) { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    $workbenchArgs = @('-jar', $workbenchJar, '--server.address=127.0.0.1', ('--server.port=' + $Port))
    if ($DisableScanners) { $workbenchArgs += @('--livecut.jobs.scan-enabled=false', '--livecut.uploads.scan-enabled=false', '--livecut.events.scan-enabled=false') }
    Write-Host ('Workbench: http://127.0.0.1:' + $Port + '/')
    Write-Host 'Starting Java only. No Python Worker is started by this command.'
    Push-Location $workbenchPackage
    try {
        $workbenchPreference = $ErrorActionPreference
        try { $ErrorActionPreference = 'Continue'; & $workbenchJava @workbenchArgs; $workbenchExit = $LASTEXITCODE } finally { $ErrorActionPreference = $workbenchPreference }
        if ($workbenchExit -ne 0) { throw 'WORKBENCH_JAVA_EXITED: see the Java output above.' }
    } finally { Pop-Location }
} finally {
    foreach ($entry in @(Get-ChildItem Env: | Where-Object { $_.Name.StartsWith('LIVECUT_') -or $_.Name -in @('DASHSCOPE_API_KEY', 'DEEPSEEK_API_KEY', 'OPENAI_API_KEY') })) { [Environment]::SetEnvironmentVariable($entry.Name, $null, 'Process') }
    foreach ($name in $workbenchSaved.Keys) { [Environment]::SetEnvironmentVariable($name, $workbenchSaved[$name], 'Process') }
}

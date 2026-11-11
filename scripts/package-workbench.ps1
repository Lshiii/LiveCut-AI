param(
    [string]$OutputRoot = 'data/distributions',
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$bundleRepo = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd([char[]]'\/')
$bundleComparison = if ($env:OS -eq 'Windows_NT') { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
if ($env:OS -eq 'Windows_NT' -and $bundleRepo.IndexOfAny([char[]]'&|<>^()%!"') -ge 0) { throw 'WORKBENCH_CMD_PATH_UNSUPPORTED' }

function Resolve-BundlePath([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOfAny([char[]]"`r`n`0") -ge 0) { throw 'WORKBENCH_INVALID_PATH' }
    $absolute = [IO.Path]::GetFullPath($(if ([IO.Path]::IsPathRooted($Value)) { $Value } else { Join-Path $bundleRepo $Value }))
    if ($env:OS -eq 'Windows_NT' -and $absolute.IndexOfAny([char[]]'&|<>^()%!"') -ge 0) { throw 'WORKBENCH_CMD_PATH_UNSUPPORTED' }
    if (-not $absolute.StartsWith($bundleRepo + [IO.Path]::DirectorySeparatorChar, $bundleComparison)) { throw 'WORKBENCH_PATH_OUTSIDE_REPOSITORY' }
    return $absolute
}
function Assert-BundlePath([string]$Path) {
    $absolute = Resolve-BundlePath $Path
    $cursor = $bundleRepo
    if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'WORKBENCH_LINK_REJECTED' }
    foreach ($part in $absolute.Substring($bundleRepo.Length + 1).Split([char[]]'\/')) {
        $cursor = Join-Path $cursor $part
        $existing = Get-Item -LiteralPath $cursor -Force -ErrorAction SilentlyContinue
        if ($null -ne $existing -and ($existing.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'WORKBENCH_LINK_REJECTED' }
    }
}
function Get-BundleFiles([string]$Directory) {
    Assert-BundlePath $Directory
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) { throw 'WORKBENCH_DIRECTORY_MISSING' }
    $stack = [Collections.Generic.Stack[string]]::new()
    $stack.Push($Directory)
    while ($stack.Count) {
        foreach ($entry in Get-ChildItem -LiteralPath $stack.Pop() -Force) {
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'WORKBENCH_LINK_REJECTED' }
            if ($entry.PSIsContainer) { $stack.Push($entry.FullName) } else { $entry }
        }
    }
}
function Get-BundleHash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-BundleSource {
    $paths = [Collections.Generic.List[string]]::new()
    foreach ($directory in @('backend-java/src/main', 'backend-java/tools', 'frontend/src', 'frontend/public')) {
        $path = Join-Path $bundleRepo $directory
        if (Test-Path -LiteralPath $path) { foreach ($file in Get-BundleFiles $path) { $paths.Add($file.FullName) } }
    }
    foreach ($name in @('backend-java/pom.xml', 'backend-java/mvnw', 'backend-java/mvnw.cmd', 'backend-java/.mvn/wrapper/maven-wrapper.properties', 'frontend/index.html', 'frontend/package.json', 'frontend/package-lock.json', 'frontend/vite.config.ts', 'scripts/package-workbench.ps1', 'scripts/run-workbench.ps1')) {
        $path = Join-Path $bundleRepo $name; Assert-BundlePath $path
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'WORKBENCH_SOURCE_MISSING' }
        $paths.Add($path)
    }
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $bundleRepo 'frontend') -Filter 'tsconfig*.json' -File) { Assert-BundlePath $file.FullName; $paths.Add($file.FullName) }
    $rows = foreach ($path in $paths) {
        $file = Get-Item -LiteralPath $path
        [ordered]@{ path = $path.Substring($bundleRepo.Length + 1).Replace('\', '/'); bytes = $file.Length; sha256 = Get-BundleHash $path }
    }
    return @($rows | Sort-Object { $_.path })
}
function Get-BundleInventory([string]$Directory) {
    $count = 0; $total = 0L
    $rows = foreach ($file in Get-BundleFiles $Directory) {
        $relative = $file.FullName.Substring($Directory.Length + 1).Replace('\', '/')
        if ($relative -cnotmatch '^(index\.html|favicon\.svg|assets/[A-Za-z0-9_./-]+)$' -or $relative -match '(^|/)\.|\.\.|\.(map|env|mp4|zip|java|ts)$') { throw 'WORKBENCH_DIST_MEMBER_REJECTED' }
        $count++; $total += $file.Length
        if ($count -gt 256 -or $file.Length -gt 16777216 -or $total -gt 67108864) { throw 'WORKBENCH_DIST_TOO_LARGE' }
        [ordered]@{ path = $relative; bytes = $file.Length; sha256 = Get-BundleHash $file.FullName }
    }
    $names = @($rows | ForEach-Object { $_.path })
    if ($names -cnotcontains 'index.html' -or $names -cnotcontains 'favicon.svg' -or -not @($names | Where-Object { $_ -cmatch '^assets/fileHash\.worker-[A-Za-z0-9_-]+\.js$' }).Count) { throw 'WORKBENCH_DIST_INCOMPLETE' }
    return @($rows | Sort-Object { $_.path })
}
function Write-BundleJson([string]$Path, $Value) { [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false)) }
function Invoke-BundleTool([string]$Program, [string[]]$ToolArguments, [string]$LogPath) {
    $command = Get-Command $Program -ErrorAction Stop
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $command @ToolArguments *> $LogPath
        $toolExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($toolExit -ne 0) { throw ('WORKBENCH_TOOL_FAILED: see ' + $LogPath) }
}
function Assert-BundleJar([string]$Path, $Inventory) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $members = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName.Contains('\') -or $entry.FullName.StartsWith('/') -or $entry.FullName.Split('/') -contains '..' -or $entry.FullName -match '(^|/)(\.env[^/]*|[^/]+\.(pem|key|p12|pfx))$') { throw 'WORKBENCH_JAR_MEMBER_REJECTED' }
            if ($members.ContainsKey($entry.FullName)) { throw 'WORKBENCH_JAR_DUPLICATE_MEMBER' }
            $members[$entry.FullName] = $entry
        }
        $actual = @($zip.Entries | Where-Object { $_.FullName.StartsWith('BOOT-INF/classes/static/') -and -not $_.FullName.EndsWith('/') })
        if ($actual.Count -ne $Inventory.Count -or -not $members.ContainsKey('META-INF/MANIFEST.MF')) { throw 'WORKBENCH_JAR_STATIC_MISMATCH' }
        foreach ($item in $Inventory) {
            $name = 'BOOT-INF/classes/static/' + $item.path
            if (-not $members.ContainsKey($name) -or $members[$name].Length -ne $item.bytes) { throw 'WORKBENCH_JAR_STATIC_MISMATCH' }
            $stream = $members[$name].Open(); $hasher = [Security.Cryptography.SHA256]::Create()
            try { $digest = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() } finally { $stream.Dispose(); $hasher.Dispose() }
            if ($digest -cne $item.sha256) { throw 'WORKBENCH_JAR_STATIC_MISMATCH' }
        }
        $reader = [IO.StreamReader]::new($members['META-INF/MANIFEST.MF'].Open())
        try { $manifestText = $reader.ReadToEnd().Replace("`r`n ", '').Replace("`n ", '') } finally { $reader.Dispose() }
        if ($manifestText -notmatch '(?m)^Main-Class: org\.springframework\.boot\.loader\.launch\.JarLauncher\r?$' -or $manifestText -notmatch '(?m)^Start-Class: io\.livecut\.[A-Za-z0-9_.]+\r?$') { throw 'WORKBENCH_JAR_NOT_EXECUTABLE' }
    } finally { $zip.Dispose() }
}

$bundleOutput = Resolve-BundlePath $OutputRoot
Assert-BundlePath $bundleOutput
if (-not $bundleOutput.StartsWith((Join-Path $bundleRepo 'data') + [IO.Path]::DirectorySeparatorChar, $bundleComparison)) { throw 'WORKBENCH_OUTPUT_MUST_BE_UNDER_DATA' }
foreach ($tool in @('node', 'npm')) { if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw ('WORKBENCH_TOOL_MISSING: ' + $tool) } }
$bundleJavaCommand = if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { 'java' } else { Join-Path $env:JAVA_HOME $(if ($env:OS -eq 'Windows_NT') { 'bin/java.exe' } else { 'bin/java' }) }
if (-not (Get-Command $bundleJavaCommand -ErrorAction SilentlyContinue)) { throw 'WORKBENCH_TOOL_MISSING: java' }
$bundleTag = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$bundleRun = Join-Path $bundleOutput $bundleTag
if (Test-Path -LiteralPath $bundleRun) { throw 'WORKBENCH_OUTPUT_EXISTS' }
New-Item -ItemType Directory -Path $bundleRun | Out-Null
$bundleRun = [IO.Path]::GetFullPath($bundleRun)
$bundleSavedEnvironment = @{}
$bundlePhase = 'SOURCE_SNAPSHOT'
try {
    foreach ($entry in @(Get-ChildItem Env: | Where-Object { $_.Name.StartsWith('LIVECUT_') -or $_.Name -in @('DASHSCOPE_API_KEY', 'DEEPSEEK_API_KEY', 'OPENAI_API_KEY') })) {
        $bundleSavedEnvironment[$entry.Name] = $entry.Value
        [Environment]::SetEnvironmentVariable($entry.Name, $null, 'Process')
    }
    $beforeSource = @(Get-BundleSource)
    Write-BundleJson (Join-Path $bundleRun 'source-files.json') $beforeSource
    $gitCommit = $null; $gitDirty = $null
    if (Get-Command git -ErrorAction SilentlyContinue) {
        Push-Location $bundleRepo
        try {
            $gitPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            $gitResult = & git rev-parse --verify HEAD 2>$null
            if ($LASTEXITCODE -eq 0 -and $gitResult -match '^[a-f0-9]{40}$') {
                $gitRoot = & git rev-parse --show-toplevel 2>$null
                if ($LASTEXITCODE -eq 0 -and [IO.Path]::GetFullPath([string]$gitRoot).Equals($bundleRepo, $bundleComparison)) { $gitCommit = [string]$gitResult; $gitDirty = [bool](& git status --porcelain --untracked-files=normal) }
            }
        } finally { $ErrorActionPreference = $gitPreference; Pop-Location }
    }
    Push-Location (Join-Path $bundleRepo 'frontend')
    try {
        foreach ($generatedPath in @('frontend/dist', 'frontend/node_modules')) {
            $generated = Join-Path $bundleRepo $generatedPath
            Assert-BundlePath $generated
            if ((Test-Path -LiteralPath $generated) -and -not (Test-Path -LiteralPath $generated -PathType Container)) { throw 'WORKBENCH_GENERATED_DIRECTORY_INVALID' }
        }
        if (-not $SkipInstall) { $bundlePhase = 'NPM_CI'; Write-Host 'Installing locked frontend dependencies...'; Invoke-BundleTool 'npm' @('ci', '--no-audit', '--no-fund') (Join-Path $bundleRun 'npm-ci.log') }
        $bundlePhase = 'FRONTEND_BUILD'; Write-Host 'Building the workbench...'; Invoke-BundleTool 'npm' @('run', 'build') (Join-Path $bundleRun 'frontend-build.log')
    } finally { Pop-Location }
    $bundlePhase = 'FREEZE_UI'
    $dist = Join-Path $bundleRepo 'frontend/dist'; $ui = Join-Path $bundleRun 'ui'
    $uiFiles = @(Get-BundleInventory $dist)
    New-Item -ItemType Directory -Path $ui | Out-Null
    foreach ($item in $uiFiles) {
        $destination = Join-Path $ui $item.path
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
        [IO.File]::Copy((Join-Path $dist $item.path), $destination, $false)
    }
    if (($uiFiles | ConvertTo-Json -Depth 6 -Compress) -cne (@(Get-BundleInventory $ui) | ConvertTo-Json -Depth 6 -Compress)) { throw 'WORKBENCH_DIST_CHANGED' }
    $bundlePhase = 'JAVA_PACKAGE'; Write-Host 'Packaging Java and the frozen workbench...'
    $javaOutput = Join-Path $bundleRun 'java-target'
    Push-Location (Join-Path $bundleRepo 'backend-java')
    try {
        $wrapper = if ($env:OS -eq 'Windows_NT') { Join-Path (Get-Location) 'mvnw.cmd' } else { Join-Path (Get-Location) 'mvnw' }
        Invoke-BundleTool $wrapper @('--batch-mode', '-Pworkbench', '-DskipTests', ('-Dworkbench.dist=' + $ui), ('-Dworkbench.build.directory=' + $javaOutput), 'package') (Join-Path $bundleRun 'java-package.log')
    } finally { Pop-Location }
    $bundlePhase = 'VERIFY_PACKAGE'
    [xml]$pom = [IO.File]::ReadAllText((Join-Path $bundleRepo 'backend-java/pom.xml'))
    $artifactName = [string]$pom.project.artifactId + '-' + [string]$pom.project.version + '.jar'
    if ($artifactName -notmatch '^[A-Za-z0-9_.-]+\.jar$') { throw 'WORKBENCH_ARTIFACT_NAME_INVALID' }
    $jar = Join-Path $javaOutput $artifactName
    Assert-BundleJar $jar $uiFiles
    $afterSource = @(Get-BundleSource)
    if (($beforeSource | ConvertTo-Json -Depth 6 -Compress) -cne ($afterSource | ConvertTo-Json -Depth 6 -Compress)) { throw 'WORKBENCH_SOURCE_CHANGED_DURING_BUILD' }
    if (($uiFiles | ConvertTo-Json -Depth 6 -Compress) -cne (@(Get-BundleInventory $dist) | ConvertTo-Json -Depth 6 -Compress)) { throw 'WORKBENCH_DIST_CHANGED_DURING_BUILD' }
    $packageDirectory = Join-Path $bundleRun 'package'
    New-Item -ItemType Directory -Path $packageDirectory | Out-Null
    $portableJar = Join-Path $packageDirectory 'livecut-workbench.jar'
    [IO.File]::Copy($jar, $portableJar, $false)
    [IO.File]::Copy((Join-Path $bundleRepo 'scripts/run-workbench.ps1'), (Join-Path $packageDirectory 'run-workbench.ps1'), $false)
    [IO.File]::Copy((Join-Path $bundleRun 'source-files.json'), (Join-Path $packageDirectory 'source-files.json'), $false)
    $example = @'
LIVECUT_DB_URL=jdbc:mysql://127.0.0.1:13306/livecut?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
LIVECUT_DB_USER=livecut
LIVECUT_DB_PASSWORD=
LIVECUT_STORAGE_ROOT=./storage
LIVECUT_WORKER_API_KEY=
LIVECUT_API_PORT=8080
'@
    [IO.File]::WriteAllText((Join-Path $packageDirectory '.env.java.example'), $example, [Text.UTF8Encoding]::new($false))
    $instructions = @'
# LiveCut workbench package

This folder contains the Java API and production web UI together. It does not include your database, videos, private credentials, or the Python Worker.

Use JDK 21 and an existing MySQL database. Copy `.env.java.example` to `.env.java`, fill the database settings, and keep private configuration outside the JAR. In PowerShell run:

    ./run-workbench.ps1

Open http://127.0.0.1:8080/ after Java starts. `-Port 5173` is optional when deliberately preserving an existing localhost origin. Do not run Vite on the same port.

Browser drafts and pending submissions belong to their original scheme/host/port. Moving from 5173 to 8080 does not migrate them. Resolve or export old pending submissions before creating new ones at another origin.

Java startup performs database migrations and normally runs maintenance scanners. `-DisableScanners` only disables scanners; it is not a read-only mode. No Python Worker is started automatically. New analysis/AI requests need a separately configured Worker; a cached-answer-only Worker can handle supported replays without provider keys.
'@
    [IO.File]::WriteAllText((Join-Path $packageDirectory 'README.md'), $instructions, [Text.UTF8Encoding]::new($false))
    $jarHash = Get-BundleHash $portableJar
    $package = [ordered]@{ schema_version = 1; kind = 'livecut-workbench-package'; source_commit = $gitCommit; source_dirty = $gitDirty;
        source_files_manifest_sha256 = Get-BundleHash (Join-Path $bundleRun 'source-files.json'); frontend_files = $uiFiles;
        dependency_installation = $(if ($SkipInstall) { 'EXISTING_NODE_MODULES' } else { 'NPM_CI_LOCKED' });
        artifact = [ordered]@{ filename = 'livecut-workbench.jar'; bytes = (Get-Item -LiteralPath $portableJar).Length; sha256 = $jarHash };
        launcher_sha256 = Get-BundleHash (Join-Path $packageDirectory 'run-workbench.ps1');
        public_context_path = '/'; private_configuration_included = $false; server_started = $false; worker_started = $false; model_requests = 0 }
    if (($beforeSource | ConvertTo-Json -Depth 6 -Compress) -cne (@(Get-BundleSource) | ConvertTo-Json -Depth 6 -Compress)) { throw 'WORKBENCH_SOURCE_CHANGED_DURING_BUILD' }
    if ($package.launcher_sha256 -cne (($beforeSource | Where-Object { $_.path -ceq 'scripts/run-workbench.ps1' }).sha256)) { throw 'WORKBENCH_LAUNCHER_CHANGED_DURING_BUILD' }
    Write-BundleJson (Join-Path $packageDirectory 'package-manifest.json') $package
    Write-BundleJson (Join-Path $bundleRun 'build-status.json') ([ordered]@{ status = 'SUCCEEDED'; phase = 'COMPLETE'; artifact = $portableJar; sha256 = $jarHash })
    Write-Host ('Workbench package folder: ' + $packageDirectory)
    Write-Host ('SHA256: ' + $jarHash)
    Write-Host 'No server, database or Worker was started. Runtime configuration stays outside the JAR.'
    [pscustomobject]@{ PackageDirectory = $packageDirectory; Jar = $portableJar; Manifest = (Join-Path $packageDirectory 'package-manifest.json'); Sha256 = $jarHash; BuildDirectory = $bundleRun }
} catch {
    Write-BundleJson (Join-Path $bundleRun 'build-status.json') ([ordered]@{ status = 'FAILED'; phase = $bundlePhase; message = $_.Exception.Message; server_started = $false; worker_started = $false })
    throw
} finally {
    foreach ($name in $bundleSavedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $bundleSavedEnvironment[$name], 'Process') }
}

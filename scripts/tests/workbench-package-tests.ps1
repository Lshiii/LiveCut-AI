$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$testRepo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$testRoot = Join-Path $testRepo ('data/workbench-script-tests/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$bundleRepo = $testRoot
$bundleComparison = if ($env:OS -eq 'Windows_NT') { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
$testCount = 0
function Assert-Case([bool]$Condition, [string]$Label) { if (-not $Condition) { throw ('Failed: ' + $Label) }; $script:testCount++ }
function Write-Fixture([string]$Name, [string]$Value) {
    $path = Join-Path $testRoot $Name; [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
    [IO.File]::WriteAllText($path, $Value, [Text.UTF8Encoding]::new($false))
}
$parseTokens = $null; $parseErrors = $null
$packageAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path $testRepo 'scripts/package-workbench.ps1'), [ref]$parseTokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Production package helper must parse.' }
# Load trusted production function declarations only, never its build entry point.
foreach ($definition in $packageAst.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
$testEnvNames = @('JAVA_HOME', 'DASHSCOPE_API_KEY', 'LIVECUT_TEXT_API_KEY', 'LIVECUT_SCRIPT_TEST_SENTINEL')
$testSaved = @{}
foreach ($name in $testEnvNames) { $testSaved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$testJavaCalls = [Collections.Generic.List[object]]::new()
$testNpmState = @{ Calls = 0 }
function java {
    $testJavaCalls.Add([pscustomobject]@{ args = @($args); storage = $env:LIVECUT_STORAGE_ROOT; font = $env:LIVECUT_CAPTION_FONT_PATH;
        provider_absent = -not $env:DASHSCOPE_API_KEY -and -not $env:LIVECUT_TEXT_API_KEY; database_fixture = $env:LIVECUT_DB_USER -eq 'synthetic-user' })
    $global:LASTEXITCODE = 0
}
function npm { $testNpmState.Calls++; $global:LASTEXITCODE = 7 }
function New-SyntheticJar([string]$Name, [string]$Variant, $Inventory) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $path = Join-Path $testRoot ($Name + '.jar')
    $archive = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $texts = [Collections.Generic.List[object]]::new()
        foreach ($item in $Inventory) {
            if ($Variant -eq 'missing' -and $item.path -eq 'index.html') { continue }
            $member = 'BOOT-INF/classes/static/' + $item.path
            if ($Variant -eq 'case' -and $item.path -eq 'index.html') { $member = 'BOOT-INF/classes/static/INDEX.HTML' }
            $bytes = [IO.File]::ReadAllBytes((Join-Path $testRoot ('ui/' + $item.path)))
            if ($Variant -eq 'tamper' -and $item.path -eq 'index.html') { $bytes = [Text.Encoding]::UTF8.GetBytes('changed') }
            $texts.Add(@{ name = $member; bytes = $bytes })
        }
        $texts.Add(@{ name = 'META-INF/MANIFEST.MF'; bytes = [Text.Encoding]::UTF8.GetBytes("Manifest-Version: 1.0`r`nMain-Class: org.springframework.boot.loader.launch.JarLauncher`r`nStart-Class: io.livecut.SyntheticFixture`r`n`r`n") })
        if ($Variant -eq 'private') { $texts.Add(@{ name = 'BOOT-INF/classes/.env.java'; bytes = [Text.Encoding]::UTF8.GetBytes('SYNTHETIC_NOT_A_KEY') }) }
        if ($Variant -eq 'duplicate') { $texts.Add(@{ name = 'BOOT-INF/classes/static/index.html'; bytes = [Text.Encoding]::UTF8.GetBytes('duplicate') }) }
        foreach ($row in $texts) { $stream = $archive.CreateEntry($row.name).Open(); try { $stream.Write($row.bytes, 0, $row.bytes.Length) } finally { $stream.Dispose() } }
    } finally { $archive.Dispose() }
    return $path
}
try {
    foreach ($name in @('backend-java/src/main/resources/application.properties', 'frontend/src/App.vue', 'frontend/public/favicon.svg', 'frontend/index.html',
        'backend-java/pom.xml', 'backend-java/mvnw', 'backend-java/mvnw.cmd', 'backend-java/.mvn/wrapper/maven-wrapper.properties',
        'frontend/package.json', 'frontend/package-lock.json', 'frontend/vite.config.ts', 'frontend/tsconfig.json')) { Write-Fixture $name 'synthetic fixture' }
    [IO.Directory]::CreateDirectory((Join-Path $testRoot 'scripts')) | Out-Null
    foreach ($name in @('package-workbench.ps1', 'run-workbench.ps1')) { [IO.File]::Copy((Join-Path $testRepo ('scripts/' + $name)), (Join-Path $testRoot ('scripts/' + $name)), $false) }
    $firstSource = @(Get-BundleSource)
    Assert-Case (@($firstSource | Where-Object { $_.path -ceq 'frontend/index.html' }).Count -eq 1) 'Vite HTML entry is frozen'
    Write-Fixture 'frontend/index.html' 'changed build entry'
    $nextSource = @(Get-BundleSource)
    Assert-Case ((($firstSource | Where-Object { $_.path -ceq 'frontend/index.html' }).sha256) -cne (($nextSource | Where-Object { $_.path -ceq 'frontend/index.html' }).sha256)) 'HTML mutation changes source fingerprint input'
    $refusedOutside = $false
    try { Resolve-BundlePath (Join-Path $testRoot '../outside') | Out-Null } catch { $refusedOutside = $_.Exception.Message -eq 'WORKBENCH_PATH_OUTSIDE_REPOSITORY' }
    Assert-Case $refusedOutside 'output cannot escape workspace'
    if ($env:OS -eq 'Windows_NT') {
        foreach ($name in @('ui&ver', 'ui%TEMP%', 'ui!value', 'ui(parent)')) {
            $rejected = $false
            try { Resolve-BundlePath (Join-Path $testRoot $name) | Out-Null } catch { $rejected = $_.Exception.Message -eq 'WORKBENCH_CMD_PATH_UNSUPPORTED' }
            Assert-Case $rejected 'cmd metacharacters are rejected before native invocation'
        }
        Invoke-BundleTool 'cmd.exe' @('/d', '/c', 'echo synthetic-warning 1>&2 & exit /b 0') (Join-Path $testRoot 'native-warning.log')
        Assert-Case ($ErrorActionPreference -eq 'Stop' -and (Get-Content -LiteralPath (Join-Path $testRoot 'native-warning.log') -Raw).Contains('synthetic-warning')) 'native stderr warning with exit zero is successful and preference restored'
        $nonzero = $false
        try { Invoke-BundleTool 'cmd.exe' @('/d', '/c', 'exit /b 7') (Join-Path $testRoot 'native-failure.log') } catch { $nonzero = $_.Exception.Message.StartsWith('WORKBENCH_TOOL_FAILED:') }
        Assert-Case ($nonzero -and $ErrorActionPreference -eq 'Stop') 'native nonzero is not ignored'
    }
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $null, 'Process')
    $env:DASHSCOPE_API_KEY = 'synthetic-inherited-provider'
    $env:LIVECUT_TEXT_API_KEY = 'synthetic-inherited-text'
    $env:LIVECUT_SCRIPT_TEST_SENTINEL = 'before-launch'
    Write-Fixture 'portable/livecut-workbench.jar' 'synthetic-container-not-a-running-application'
    [IO.File]::Copy((Join-Path $testRepo 'scripts/run-workbench.ps1'), (Join-Path $testRoot 'portable/run-workbench.ps1'), $false)
    $fakeJar = Join-Path $testRoot 'portable/livecut-workbench.jar'
    Write-BundleJson (Join-Path $testRoot 'portable/package-manifest.json') ([ordered]@{ schema_version = 1; kind = 'livecut-workbench-package'; public_context_path = '/'; artifact = @{ filename = 'livecut-workbench.jar'; bytes = (Get-Item -LiteralPath $fakeJar).Length; sha256 = Get-BundleHash $fakeJar } })
    Write-Fixture 'portable/.env.java' "LIVECUT_DB_URL=jdbc:mysql://synthetic.invalid/not-contacted`nLIVECUT_DB_USER=synthetic-user`nLIVECUT_DB_PASSWORD=synthetic-password`nLIVECUT_STORAGE_ROOT=./storage`nLIVECUT_CAPTION_FONT_PATH=./font.ttf`nLIVECUT_API_PORT=8080`n"
    & (Join-Path $testRoot 'portable/run-workbench.ps1') -Port 5173 -DisableScanners 6>$null
    Assert-Case ($testJavaCalls.Count -eq 1 -and $testJavaCalls[0].provider_absent -and $testJavaCalls[0].database_fixture) 'launcher only invokes mocked Java with explicit config and scrubbed inherited provider keys'
    Assert-Case ($testJavaCalls[0].args -contains '--server.address=127.0.0.1' -and $testJavaCalls[0].args -contains '--server.port=5173') 'port is explicit and bound to loopback'
    Assert-Case (@($testJavaCalls[0].args | Where-Object { $_ -like '--livecut.*.scan-enabled=false' }).Count -eq 3) 'optional scanner disable flags are exact'
    Assert-Case ($testJavaCalls[0].storage -eq [IO.Path]::GetFullPath((Join-Path $testRoot 'portable/storage'))) 'portable relative storage resolves beside package'
    Assert-Case ($testJavaCalls[0].font -eq [IO.Path]::GetFullPath((Join-Path $testRoot 'portable/font.ttf'))) 'portable relative font resolves beside package'
    Assert-Case ($env:DASHSCOPE_API_KEY -eq 'synthetic-inherited-provider' -and $env:LIVECUT_TEXT_API_KEY -eq 'synthetic-inherited-text' -and $env:LIVECUT_SCRIPT_TEST_SENTINEL -eq 'before-launch') 'launcher restores original process environment'
    Write-Fixture 'portable/.env.legacy' ([IO.File]::ReadAllText((Join-Path $testRoot 'portable/.env.java')).Replace('LIVECUT_STORAGE_ROOT=./storage', 'LIVECUT_STORAGE_ROOT=../storage'))
    & (Join-Path $testRepo 'scripts/run-workbench.ps1') -PackageDirectory (Join-Path $testRoot 'portable') -EnvFile (Join-Path $testRoot 'portable/.env.legacy') 6>$null
    Assert-Case ($testJavaCalls.Count -eq 2 -and $testJavaCalls[1].storage -eq [IO.Path]::GetFullPath((Join-Path $testRepo 'storage'))) 'repository launcher preserves original backend-relative parent storage semantics'
    [IO.File]::AppendAllText($fakeJar, 'tampered')
    $badJar = $false
    try { & (Join-Path $testRoot 'portable/run-workbench.ps1') 6>$null } catch { $badJar = $_.Exception.Message -eq 'WORKBENCH_PACKAGE_HASH_MISMATCH' }
    Assert-Case ($badJar -and $testJavaCalls.Count -eq 2) 'tampered jar is refused before Java'
    $testNpmState.Calls = 0
    $sourceFailure = $false
    try { & (Join-Path $testRoot 'scripts/package-workbench.ps1') 6>$null } catch { $sourceFailure = $_.Exception.Message.StartsWith('WORKBENCH_TOOL_FAILED:') }
    Assert-Case ($sourceFailure -and $testNpmState.Calls -eq 1) 'packaging fails on dependency installation failure'
    Assert-Case ($env:DASHSCOPE_API_KEY -eq 'synthetic-inherited-provider' -and $env:LIVECUT_TEXT_API_KEY -eq 'synthetic-inherited-text') 'failed build restores inherited keys without using them'
    $failedStatus = @(Get-ChildItem -LiteralPath (Join-Path $testRoot 'data/distributions') -Recurse -Filter 'build-status.json' -File)
    Assert-Case ($failedStatus.Count -eq 1 -and ((Get-Content -LiteralPath $failedStatus[0].FullName -Raw | ConvertFrom-Json).status -eq 'FAILED')) 'failed build evidence is kept and no success manifest written'
    if ($env:OS -eq 'Windows_NT') {
        [IO.Directory]::CreateDirectory((Join-Path $testRoot 'outside-dist')) | Out-Null
        $link = Join-Path $testRoot 'frontend/dist'
        New-Item -ItemType Junction -Path $link -Target (Join-Path $testRoot 'outside-dist') | Out-Null
        $testNpmState.Calls = 0; $linkRefused = $false
        try { & (Join-Path $testRoot 'scripts/package-workbench.ps1') 6>$null } catch { $linkRefused = $_.Exception.Message -eq 'WORKBENCH_LINK_REJECTED' }
        Assert-Case ($linkRefused -and $testNpmState.Calls -eq 0) 'existing dist junction is refused before npm cleanup or writing'
        # Remove only the verified fixture junction itself; never recurse into its target.
        [IO.Directory]::Delete($link)
    }
    foreach ($name in @('index.html', 'favicon.svg', 'assets/index-fixture.js', 'assets/index-fixture.css', 'assets/fileHash.worker-fixture.js')) { Write-Fixture ('ui/' + $name) ('synthetic asset: ' + $name) }
    $inventory = @(Get-BundleInventory (Join-Path $testRoot 'ui'))
    Assert-BundleJar (New-SyntheticJar 'consistent-static-fixture' 'valid' $inventory) $inventory
    Assert-Case $true 'synthetic consistent static inventory passes archive integrity only'
    foreach ($variant in @('missing', 'tamper', 'private', 'duplicate', 'case')) {
        $refused = $false
        try { Assert-BundleJar (New-SyntheticJar ('rejected-' + $variant) $variant $inventory) $inventory } catch { $refused = $_.Exception.Message.StartsWith('WORKBENCH_JAR_') }
        Assert-Case $refused ('archive rejects ' + $variant + ' without running its content')
    }
    [pscustomobject]@{ status = 'passed'; cases = $testCount; kind = 'SYNTHETIC_SCRIPT_TESTS'; actual_servers = 0; actual_workers = 0; actual_model_requests = 0 } | ConvertTo-Json -Compress
} finally {
    foreach ($name in $testSaved.Keys) { [Environment]::SetEnvironmentVariable($name, $testSaved[$name], 'Process') }
    # Preserve tiny synthetic files/failure logs under ignored data for inspection.
}

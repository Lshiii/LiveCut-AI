param(
    [Parameter(Mandatory = $true)][string]$MysqldPath,
    [switch]$RunScratch
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $RunScratch -or $env:OS -ne 'Windows_NT') { throw 'Pass -RunScratch on Windows to run the isolated 13316 smoke test.' }
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$helper = Join-Path $repoRoot 'scripts/mysql-local.ps1'
$binary = [IO.Path]::GetFullPath($MysqldPath)
$binDirectory = Split-Path -Parent $binary
$workspace = Join-Path ([IO.Path]::GetTempPath()) ('livecut-mysql-helper-test-' + [Guid]::NewGuid().ToString('N'))
$workspace = [IO.Path]::GetFullPath($workspace)
$data = Join-Path $workspace 'livecut-mysql-data'
$config = Join-Path $workspace 'helper.env'
$initFile = Join-Path $workspace 'bootstrap-private.sql'
$clientFile = Join-Path $workspace 'client-private.cnf'
$reportPath = Join-Path $repoRoot 'data/runs/mysql-local-helper/report.json'
$checks = [Collections.Generic.List[string]]::new()
$report = [ordered]@{ passed = $false; started_at = [DateTime]::UtcNow.ToString('o'); port = 13316; workspace = $workspace; checks = @() }
$password = 'Lc!' + [Guid]::NewGuid().ToString('N') + '#;"\x'
$rootBefore = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 13306 | Select-Object -ExpandProperty OwningProcess)
$serviceBefore = Get-CimInstance Win32_Service -Filter "Name='MySQL80'" | Select-Object State,ProcessId

function Private-Write([string]$Path, [string]$Content) {
    [IO.File]::WriteAllText($Path, '')
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow'))
    Set-Acl -LiteralPath $Path -AclObject $acl
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Write-Config([string]$Path, [string]$Directory, [string]$PasswordValue) {
    Private-Write $Path ((@("LIVECUT_MYSQLD_PATH=$binary", "LIVECUT_MYSQL_DATADIR=$Directory", 'LIVECUT_MYSQL_PORT=13316',
        'LIVECUT_MYSQL_USER=root', "LIVECUT_MYSQL_PASSWORD=$PasswordValue")) -join "`n")
}

function Run-Native([string]$Executable, [string[]]$Arguments, [int]$Seconds = 30) {
    $launch = [Diagnostics.ProcessStartInfo]::new()
    $launch.FileName = $Executable; $launch.UseShellExecute = $false; $launch.CreateNoWindow = $true
    $launch.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    foreach ($argument in $Arguments) { $launch.ArgumentList.Add($argument) }
    $launch.RedirectStandardOutput = $true; $launch.RedirectStandardError = $true
    $launch.EnvironmentVariables['MYSQL_TEST_LOGIN_FILE'] = $clientFile + '.absent-login'
    $launch.EnvironmentVariables.Remove('MYSQL_PWD')
    $process = [Diagnostics.Process]::Start($launch)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($Seconds * 1000)) {
            # mysqld must never be force-stopped, even when its initialization fails.
            if ([IO.Path]::GetFileName($Executable) -ine 'mysqld.exe') { $process.Kill() }
            throw 'SMOKE_NATIVE_TIMEOUT'
        }
        $result = $stdout.GetAwaiter().GetResult(); [void]$stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'SMOKE_NATIVE_FAILED' }
        return $result.Trim()
    } finally { $process.Dispose() }
}

function Query([string]$Sql) {
    return Run-Native (Join-Path $binDirectory 'mysql.exe') @("--defaults-file=$clientFile", '--protocol=TCP', '--host=127.0.0.1',
        '--port=13316', '--connect-timeout=3', '--batch', '--raw', '--skip-column-names', "--execute=$Sql")
}

function Expect-Code([string]$Expected, [scriptblock]$Action) {
    $observed = $false
    try { & $Action | Out-Null }
    catch { if ($_.Exception.Message.StartsWith($Expected + ':')) { $observed = $true } else { throw 'SMOKE_UNEXPECTED_REJECTION' } }
    if (-not $observed) { throw 'SMOKE_EXPECTED_REFUSAL_NOT_OBSERVED' }
}

function Check([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw ('SMOKE_ASSERT_' + $Name) }
    $checks.Add($Name)
}

try {
    if (@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 13316).Count -ne 0) { throw 'SMOKE_PORT_ALREADY_IN_USE' }
    if (Test-Path -LiteralPath $workspace) { throw 'SMOKE_WORKSPACE_ALREADY_EXISTS' }
    [IO.Directory]::CreateDirectory($data) | Out-Null
    # Initialization is confined to the new UUID directory this test just made.
    Run-Native $binary @('--no-defaults', '--initialize-insecure', "--basedir=$(Split-Path -Parent $binDirectory)", "--datadir=$data", "--log-error=$(Join-Path $workspace 'initialize.err')") 90 | Out-Null
    Check ((Test-Path -LiteralPath (Join-Path $data 'auto.cnf')) -and (Test-Path -LiteralPath (Join-Path $data 'mysql'))) 'new_scratch_initialized_only'
    $sqlPassword = $password.Replace('\', '\\').Replace("'", "''")
    Private-Write $initFile ("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + $sqlPassword + "';`n")
    $clientPassword = $password.Replace('\', '\\').Replace('"', '\"')
    Private-Write $clientFile ("[client]`nuser=root`n" + 'password="' + $clientPassword + '"' + "`n")
    Write-Config $config $data $password
    $bootstrapArgs = @('--no-defaults', "--basedir=$(Split-Path -Parent $binDirectory)", "--datadir=$data", '--port=13316',
        '--bind-address=127.0.0.1', '--mysqlx=0', '--upgrade=NONE', '--persisted-globals-load=OFF',
        "--init-file=$initFile", "--log-error=$(Join-Path $workspace 'bootstrap.err')")
    # Paths have no embedded quotes; quote each complete native argument for spaces.
    $bootstrap = Start-Process -FilePath $binary -ArgumentList (($bootstrapArgs | ForEach-Object { '"' + $_ + '"' }) -join ' ') `
        -WorkingDirectory $data -WindowStyle Hidden -PassThru
    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $status = & $helper -Task Status -EnvFile $config -Scratch -Port 13316
        if ($status.status -eq 'RUNNING') { $ready = $true; break }
    }
    Check $ready 'bootstrap_password_set_before_normal_helper_use'
    Remove-Item -LiteralPath $initFile -Force
    $initialPid = [int]$status.pid
    Check (($password.Contains('"')) -and $password.Contains('\') -and $status.identity_verified) 'quoted_password_and_space_in_binary_path'
    $again = & $helper -Task Start -EnvFile $config -Scratch -Port 13316
    Check ($again.status -eq 'ALREADY_RUNNING' -and $again.pid -eq $initialPid) 'start_is_idempotent'
    Query 'CREATE DATABASE livecut_helper_smoke; CREATE TABLE livecut_helper_smoke.proof (id INT PRIMARY KEY, value_text VARCHAR(32)); INSERT INTO livecut_helper_smoke.proof VALUES (1,"persistent")' | Out-Null

    $emptyConfig = Join-Path $workspace 'empty.env'; Write-Config $emptyConfig $data ''
    Expect-Code 'MYSQL_CREDENTIAL_REQUIRED' { & $helper -Task Status -EnvFile $emptyConfig -Scratch -Port 13316 }
    $wrongConfig = Join-Path $workspace 'wrong-password.env'; Write-Config $wrongConfig $data 'intentionally-wrong-test-value'
    Expect-Code 'MYSQL_CLIENT_FAILED' { & $helper -Task Status -EnvFile $wrongConfig -Scratch -Port 13316 }
    Expect-Code 'MYSQL_CLIENT_FAILED' { & $helper -Task Stop -EnvFile $wrongConfig -Scratch -Port 13316 }
    Check ((& $helper -Task Status -EnvFile $config -Scratch -Port 13316).pid -eq $initialPid) 'wrong_password_does_not_stop_server'
    Expect-Code 'MYSQL_PORT_REFUSED' { & $helper -Task Stop -EnvFile $config -Port 3306 }
    Expect-Code 'MYSQL_PORT_REFUSED' { & $helper -Task Stop -EnvFile $config -Scratch -Port 13306 }
    $wrongData = Join-Path $workspace 'livecut-mysql-wrong'
    [IO.Directory]::CreateDirectory((Join-Path $wrongData 'mysql')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $wrongData 'auto.cnf'), '{}')
    Expect-Code 'MYSQL_IDENTITY_MISMATCH' { & $helper -Task Stop -EnvFile $config -DataDirectory $wrongData -Scratch -Port 13316 }
    Expect-Code 'MYSQL_IDENTITY_MISMATCH' { & $helper -Task Start -EnvFile $config -DataDirectory $wrongData -Scratch -Port 13316 }
    Check ((Query 'SELECT value_text FROM livecut_helper_smoke.proof WHERE id=1') -eq 'persistent') 'wrong_datadir_and_port_refused_without_data_change'
    $uninitialized = Join-Path $workspace 'livecut-mysql-uninitialized'; [IO.Directory]::CreateDirectory($uninitialized) | Out-Null
    Expect-Code 'MYSQL_DATADIR_NOT_INITIALIZED' { & $helper -Task Start -EnvFile $config -DataDirectory $uninitialized -Scratch -Port 13316 }
    Check (@(Get-ChildItem -LiteralPath $uninitialized -Force).Count -eq 0) 'helper_does_not_initialize_existing_empty_directory'

    $stopped = & $helper -Task Stop -EnvFile $config -Scratch -Port 13316
    Check ($stopped.status -eq 'STOPPED' -and $null -eq (Get-CimInstance Win32_Process -Filter "ProcessId=$initialPid")) 'stop_waits_for_original_process_exit'
    Check ((& $helper -Task Stop -EnvFile $config -Scratch -Port 13316).status -eq 'ALREADY_STOPPED') 'stop_is_idempotent'
    $busy = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 13316)
    try {
        $busy.Start()
        Expect-Code 'MYSQL_IDENTITY_MISMATCH' { & $helper -Task Start -EnvFile $config -Scratch -Port 13316 }
        Check $true 'foreign_port_listener_refused'
    } finally { $busy.Stop() }
    # A bogus pidfile is not authority to stop any process, especially 13306.
    $bogusPid = if ($rootBefore.Count -gt 0) { $rootBefore[0] } else { $PID }
    [IO.File]::WriteAllText((Join-Path $data 'livecut-local.pid'), [string]$bogusPid)
    $restarted = & $helper -Task Start -EnvFile $config -Scratch -Port 13316
    Check ($restarted.status -eq 'STARTED' -and $restarted.pid -ne $bogusPid) 'stale_pidfile_is_not_control_authority'
    Check ((Query 'SELECT value_text FROM livecut_helper_smoke.proof WHERE id=1') -eq 'persistent') 'data_survives_graceful_restart'
    [void](& $helper -Task Stop -EnvFile $config -Scratch -Port 13316)
    Check ($null -eq (Get-CimInstance Win32_Process -Filter "ProcessId=$($restarted.pid)")) 'restarted_process_exited_normally'
    $rootAfter = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq 13306 | Select-Object -ExpandProperty OwningProcess)
    $serviceAfter = Get-CimInstance Win32_Service -Filter "Name='MySQL80'" | Select-Object State,ProcessId
    Check (($rootBefore -join ',') -eq ($rootAfter -join ',')) 'project_13306_listener_unchanged'
    Check (($serviceBefore | ConvertTo-Json -Compress) -eq ($serviceAfter | ConvertTo-Json -Compress)) 'existing_mysql80_service_unchanged'
    $report.passed = $true
} catch {
    $report.failure_code = if ($_.Exception.Message -match '^([A-Z_]+)') { $Matches[1] } else { 'UNEXPECTED' }
} finally {
    # Preserve scratch data for inspection; never recursively remove a datadir.
    if (Test-Path -LiteralPath $config) {
        try { [void](& $helper -Task Stop -EnvFile $config -Scratch -Port 13316) } catch { $report.cleanup = 'graceful_stop_refused_or_failed_no_force_kill' }
    }
    if (Test-Path -LiteralPath $initFile) { Remove-Item -LiteralPath $initFile -Force }
    $report.checks = @($checks.ToArray()); $report.finished_at = [DateTime]::UtcNow.ToString('o')
    [IO.Directory]::CreateDirectory((Split-Path -Parent $reportPath)) | Out-Null
    [IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 5))
    $password = ''; $sqlPassword = ''; $clientPassword = ''
}
if (-not $report.passed) { throw 'MYSQL_SMOKE_FAILED: see ignored data/runs/mysql-local-helper/report.json (no credentials in report).' }
[pscustomobject]@{ status = 'PASSED'; checks = $checks.Count; report = $reportPath; scratch_data_preserved = $true }

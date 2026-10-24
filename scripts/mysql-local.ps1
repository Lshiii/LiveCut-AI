param(
    [ValidateSet('Status', 'Start', 'Stop')]
    [string]$Task = 'Status',
    [string]$EnvFile = '.env.mysql-local',
    [string]$MysqldPath = '',
    [string]$DataDirectory = '',
    [int]$Port = 0,
    [string]$CredentialsFile = '',
    [ValidateRange(5, 120)]
    [int]$TimeoutSeconds = 45,
    [switch]$Scratch
)

# This helper never initializes, deletes, moves, upgrades or force-kills a DB.
# The optional scratch port is reserved for this helper's isolated smoke test.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($env:OS -ne 'Windows_NT') { throw 'MYSQL_WINDOWS_REQUIRED: use the documented Compose setup on other platforms.' }
$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-LocalPath([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOfAny([char[]]("`r`n`0" + '"')) -ge 0) { throw 'MYSQL_INVALID_PATH: provide a local filesystem path.' }
    $expanded = [Environment]::ExpandEnvironmentVariables($Value)
    $absolute = if ([IO.Path]::IsPathRooted($expanded)) { $expanded } else { Join-Path $repoRoot $expanded }
    return [IO.Path]::GetFullPath($absolute).TrimEnd([char[]]'\/')
}

function Read-LocalEnvironment([string]$Path, [bool]$LegacyCredential = $false) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'MYSQL_CONFIG_NOT_FOUND: copy and fill the private configuration example first.' }
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'MYSQL_INVALID_CONFIG: expected literal NAME=value lines.' }
        $entryName, $entryValue = $Matches[1], $Matches[2]
        if (($LegacyCredential -and $entryName -ne 'MYSQL_PWD') -or (-not $LegacyCredential -and $entryName -notin @(
                'LIVECUT_MYSQLD_PATH', 'LIVECUT_MYSQL_DATADIR', 'LIVECUT_MYSQL_PORT',
                'LIVECUT_MYSQL_USER', 'LIVECUT_MYSQL_PASSWORD', 'LIVECUT_MYSQL_CREDENTIALS_FILE'))) { throw 'MYSQL_INVALID_CONFIG: unsupported configuration field.' }
        if ($values.ContainsKey($entryName)) { throw 'MYSQL_INVALID_CONFIG: duplicate configuration field.' }
        if ($entryValue.IndexOfAny([char[]]"`r`n`0") -ge 0) { throw 'MYSQL_INVALID_CONFIG: control characters are not accepted.' }
        $values[$entryName] = $entryValue
    }
    return $values
}

function Assert-ProjectDirectory([string]$Directory) {
    if ((Split-Path -Leaf $Directory) -notmatch '^livecut-mysql-[A-Za-z0-9_-]+$') { throw 'MYSQL_UNSCOPED_DATADIR: independent data directory name must start with livecut-mysql-.' }
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) { throw 'MYSQL_DATADIR_NOT_FOUND: this helper does not create or initialize databases.' }
    $cursor = Get-Item -LiteralPath $Directory -Force
    while ($null -ne $cursor) {
        if (($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'MYSQL_UNSAFE_DATADIR: reparse-point data directories or ancestors are not managed.' }
        $cursor = $cursor.Parent
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Directory 'auto.cnf') -PathType Leaf) -or
            -not (Test-Path -LiteralPath (Join-Path $Directory 'mysql') -PathType Container)) { throw 'MYSQL_DATADIR_NOT_INITIALIZED: only an explicitly prepared existing MySQL data directory is accepted.' }
}

# Parse the actual Windows command line, including paths containing spaces.
if (-not ('LiveCut.MySqlLocal.Arguments' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace LiveCut.MySqlLocal {
    public static class Arguments {
        [DllImport("shell32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
        private static extern IntPtr CommandLineToArgvW(string command, out int count);
        [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr pointer);
        public static string[] Parse(string command) {
            int count;
            IntPtr pointer = CommandLineToArgvW(command, out count);
            if (pointer == IntPtr.Zero) throw new InvalidOperationException("Cannot inspect process arguments");
            try {
                string[] values = new string[count];
                for (int index=0; index<count; index++)
                    values[index] = Marshal.PtrToStringUni(Marshal.ReadIntPtr(pointer, index*IntPtr.Size));
                return values;
            } finally { LocalFree(pointer); }
        }
    }
}
'@
}

function Get-Option([string[]]$Arguments, [string]$Name) {
    $found = @()
    for ($index = 1; $index -lt $Arguments.Count; $index++) {
        if ($Arguments[$index] -ieq "--$Name" -and $index + 1 -lt $Arguments.Count) { $index++; $found += $Arguments[$index] }
        elseif ($Arguments[$index].StartsWith("--$Name=", [StringComparison]::OrdinalIgnoreCase)) { $found += $Arguments[$index].Substring($Name.Length + 3) }
    }
    if ($found.Count -ne 1) { return $null }
    return $found[0]
}

function Get-Listeners {
    # Query the collection first: a missing port is not confused with a CIM error.
    return @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object LocalPort -eq $script:settings.Port)
}

function Assert-ProcessIdentity([int]$ProcessId) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction Stop
    if ($null -eq $process -or [string]::IsNullOrEmpty($process.ExecutablePath) -or
            (Resolve-LocalPath $process.ExecutablePath) -ine $script:settings.Executable) { throw 'MYSQL_IDENTITY_MISMATCH: listener is not the configured mysqld executable.' }
    $arguments = [LiveCut.MySqlLocal.Arguments]::Parse($process.CommandLine)
    $directory = Get-Option $arguments 'datadir'
    $processPort = Get-Option $arguments 'port'
    $bind = Get-Option $arguments 'bind-address'
    if ($arguments -notcontains '--no-defaults' -or $null -eq $directory -or
            (Resolve-LocalPath $directory) -ine $script:settings.DataDirectory -or
            $processPort -ne [string]$script:settings.Port -or $bind -ne '127.0.0.1') { throw 'MYSQL_IDENTITY_MISMATCH: explicit data directory, port, loopback and no-defaults must match.' }
    $chain = @([int]$process.ProcessId)
    $parentId = [int]$process.ParentProcessId
    for ($depth = 0; $depth -lt 4; $depth++) {
        $parent = Get-CimInstance Win32_Process -Filter "ProcessId=$parentId" -ErrorAction Stop
        if ($null -eq $parent -or [string]::IsNullOrEmpty($parent.ExecutablePath) -or (Resolve-LocalPath $parent.ExecutablePath) -ine $script:settings.Executable) { break }
        $chain += [int]$parent.ProcessId
        $parentId = [int]$parent.ParentProcessId
    }
    if (@(Get-CimInstance Win32_Service -ErrorAction Stop | Where-Object { $_.ProcessId -gt 0 -and $chain -contains [int]$_.ProcessId }).Count -ne 0) {
        throw 'MYSQL_SERVICE_REFUSED: Windows services, including MySQL80, are never managed by this helper.'
    }
    $process | Add-Member -NotePropertyName VerifiedMysqlProcessIds -NotePropertyValue $chain -Force
    return $process
}

function Get-DataProcesses {
    return @(Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" -ErrorAction Stop | Where-Object {
        # A different mysqld installation may also hold this directory. This
        # scan only refuses a second start; it never grants shutdown authority.
        if (-not $_.CommandLine) { return $false }
        $candidateData = Get-Option ([LiveCut.MySqlLocal.Arguments]::Parse($_.CommandLine)) 'datadir'
        return $candidateData -and (Resolve-LocalPath $candidateData) -ieq $script:settings.DataDirectory
    })
}

function Get-VerifiedListener {
    $listeners = @(Get-Listeners)
    if ($listeners.Count -eq 0) { return $null }
    if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1') { throw 'MYSQL_PORT_CONFLICT: expected exactly one loopback listener.' }
    return Assert-ProcessIdentity ([int]$listeners[0].OwningProcess)
}

function New-PrivateFile([string]$Suffix) {
    $path = Join-Path ([IO.Path]::GetTempPath()) ("livecut-mysql-client-" + [Guid]::NewGuid().ToString('N') + $Suffix)
    [IO.File]::WriteAllText($path, '')
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow'))
    Set-Acl -LiteralPath $path -AclObject $acl
    return $path
}

function Quote-WindowsArgument([string]$Value) {
    $builder = [Text.StringBuilder]::new('"')
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') { $slashes++; continue }
        if ($character -eq '"') { [void]$builder.Append(('\' * (2 * $slashes + 1))); [void]$builder.Append('"') }
        else { [void]$builder.Append(('\' * $slashes)); [void]$builder.Append($character) }
        $slashes = 0
    }
    [void]$builder.Append(('\' * (2 * $slashes)))
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Invoke-PrivateClient([string]$Executable, [string[]]$Arguments, [int]$ClientTimeout = 12) {
    $config = $null
    $client = $null
    try {
        $config = New-PrivateFile '.cnf'
        $escapedUser = $script:settings.User.Replace('\', '\\').Replace('"', '\"')
        $escapedPassword = $script:settings.Password.Replace('\', '\\').Replace('"', '\"')
        $clientOptions = "[client]`n" + 'user="' + $escapedUser + '"' + "`n" + 'password="' + $escapedPassword + '"' + "`n"
        [IO.File]::WriteAllText($config, $clientOptions, [Text.UTF8Encoding]::new($false))
        $argumentsWithDefaults = @("--defaults-file=$config", '--protocol=TCP', '--host=127.0.0.1',
            "--port=$($script:settings.Port)", '--connect-timeout=3') + $Arguments
        $launch = [Diagnostics.ProcessStartInfo]::new()
        $launch.FileName = $Executable
        $launch.Arguments = ($argumentsWithDefaults | ForEach-Object { Quote-WindowsArgument $_ }) -join ' '
        $launch.UseShellExecute = $false; $launch.CreateNoWindow = $true
        $launch.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
        $launch.RedirectStandardOutput = $true; $launch.RedirectStandardError = $true
        # MySQL 8.0 still reads .mylogin.cnf with --defaults-file. Override that
        # login file only in this child's environment; never change the caller.
        $launch.EnvironmentVariables['MYSQL_TEST_LOGIN_FILE'] = $config + '.absent-login'
        $launch.EnvironmentVariables.Remove('MYSQL_PWD')
        $client = [Diagnostics.Process]::Start($launch)
        $stdout = $client.StandardOutput.ReadToEndAsync()
        $stderr = $client.StandardError.ReadToEndAsync()
        if (-not $client.WaitForExit($ClientTimeout * 1000)) {
            # Only this short-lived client is ours; never force-kill mysqld.
            $client.Kill(); [void]$client.WaitForExit(3000)
            throw 'MYSQL_CLIENT_TIMEOUT: server was not force-stopped.'
        }
        $client.Refresh()
        $result = $stdout.GetAwaiter().GetResult()
        [void]$stderr.GetAwaiter().GetResult()
        if ($client.ExitCode -ne 0) { throw 'MYSQL_CLIENT_FAILED: connection, authorization or graceful shutdown failed; server was not force-stopped.' }
        return $result.Trim()
    } finally {
        if ($null -ne $client) { $client.Dispose() }
        if ($config -and (Test-Path -LiteralPath $config)) { Remove-Item -LiteralPath $config -Force }
    }
}

function Assert-ServerIdentity {
    $result = Invoke-PrivateClient $script:settings.Client @('--batch', '--raw', '--skip-column-names', '--execute=SELECT @@datadir, @@port, @@bind_address')
    $values = $result -split "`t"
    if ($values.Count -ne 3 -or (Resolve-LocalPath $values[0]) -ine $script:settings.DataDirectory -or
            $values[1] -ne [string]$script:settings.Port -or $values[2] -ne '127.0.0.1') { throw 'MYSQL_SERVER_IDENTITY_MISMATCH: authenticated server variables do not match the independent instance.' }
}

function Write-ProcessState($Process) {
    $directory = Join-Path $repoRoot 'data'
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $state = @{ pid = [int]$Process.ProcessId; parent_pid = [int]$Process.ParentProcessId; port = $script:settings.Port;
        executable = $script:settings.Executable; datadir = $script:settings.DataDirectory; creation_time = $Process.CreationDate.ToUniversalTime().ToString('o') }
    $temporary = $script:settings.StateFile + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try { [IO.File]::WriteAllText($temporary, ($state | ConvertTo-Json)); Move-Item -LiteralPath $temporary -Destination $script:settings.StateFile -Force }
    finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

$config = Read-LocalEnvironment (Resolve-LocalPath $EnvFile)
if (-not $MysqldPath) { $MysqldPath = $config['LIVECUT_MYSQLD_PATH'] }
if (-not $DataDirectory) { $DataDirectory = $config['LIVECUT_MYSQL_DATADIR'] }
if ($Port -eq 0) { $Port = if ($config['LIVECUT_MYSQL_PORT']) { [int]$config['LIVECUT_MYSQL_PORT'] } else { 13306 } }
if (($Scratch -and $Port -ne 13316) -or (-not $Scratch -and $Port -ne 13306)) { throw 'MYSQL_PORT_REFUSED: use project port 13306, or explicit -Scratch with 13316 for the isolated smoke test.' }
$binary = Resolve-LocalPath $MysqldPath
$data = Resolve-LocalPath $DataDirectory
Assert-ProjectDirectory $data
if ((Split-Path -Leaf $binary) -ine 'mysqld.exe' -or -not (Test-Path -LiteralPath $binary -PathType Leaf)) { throw 'MYSQL_BINARY_NOT_FOUND: configure the existing mysqld.exe installation.' }
$binDirectory = Split-Path -Parent $binary
$client = Join-Path $binDirectory 'mysql.exe'
$admin = Join-Path $binDirectory 'mysqladmin.exe'
if (-not (Test-Path -LiteralPath $client -PathType Leaf) -or -not (Test-Path -LiteralPath $admin -PathType Leaf)) { throw 'MYSQL_CLIENT_NOT_FOUND: mysql.exe and mysqladmin.exe must accompany mysqld.exe.' }
if (-not $CredentialsFile) { $CredentialsFile = $config['LIVECUT_MYSQL_CREDENTIALS_FILE'] }
$password = if ($CredentialsFile) {
    if ($config['LIVECUT_MYSQL_PASSWORD']) { throw 'MYSQL_INVALID_CONFIG: choose one password source.' }
    (Read-LocalEnvironment (Resolve-LocalPath $CredentialsFile) $true)['MYSQL_PWD']
} else { $config['LIVECUT_MYSQL_PASSWORD'] }
if ([string]::IsNullOrWhiteSpace([string]$password) -or $password -like 'REPLACE_*') { throw 'MYSQL_CREDENTIAL_REQUIRED: fill the private password or credential-file setting.' }
$user = if ($config['LIVECUT_MYSQL_USER']) { $config['LIVECUT_MYSQL_USER'] } else { 'root' }
$script:settings = @{ Executable = $binary; Client = $client; Admin = $admin; DataDirectory = $data; Port = $Port;
    User = $user; Password = [string]$password; StateFile = (Join-Path $repoRoot "data/mysql-local-$Port.json") }
$mutex = [Threading.Mutex]::new($false, "Local\LiveCutAI-MySQL-$Port")
$locked = $false
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw 'MYSQL_HELPER_BUSY: another helper operation is in progress.' }
    $running = Get-VerifiedListener
    if ($Task -eq 'Status') {
        if ($null -eq $running) {
            $status = if (@(Get-DataProcesses).Count -eq 0) { 'STOPPED' } else { 'STARTING_OR_STOPPING' }
            [pscustomobject]@{ status = $status; port = $Port; identity_verified = $false }; return
        }
        Assert-ServerIdentity
        [pscustomobject]@{ status = 'RUNNING'; port = $Port; pid = [int]$running.ProcessId; identity_verified = $true }; return
    }
    if ($Task -eq 'Start') {
        if ($null -ne $running) {
            Assert-ServerIdentity; Write-ProcessState $running
            [pscustomobject]@{ status = 'ALREADY_RUNNING'; port = $Port; pid = [int]$running.ProcessId; identity_verified = $true }; return
        }
        if (@(Get-DataProcesses).Count -ne 0) { throw 'MYSQL_DATADIR_BUSY: an existing mysqld references this data directory; no second process was started.' }
        $serverArguments = @('--no-defaults', "--basedir=$(Split-Path -Parent $binDirectory)", "--datadir=$data", "--port=$Port",
            '--bind-address=127.0.0.1', '--mysqlx=0', '--upgrade=NONE', '--persisted-globals-load=OFF', '--secure-file-priv=NULL',
            "--pid-file=$(Join-Path $data 'livecut-local.pid')", "--log-error=$(Join-Path $data 'livecut-local.err')")
        $launcher = Start-Process -FilePath $binary -ArgumentList (($serverArguments | ForEach-Object { Quote-WindowsArgument $_ }) -join ' ') `
            -WorkingDirectory $data -WindowStyle Hidden -PassThru
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ([DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 500
            $running = Get-VerifiedListener
            if ($null -ne $running) {
                Assert-ServerIdentity; Write-ProcessState $running
                [pscustomobject]@{ status = 'STARTED'; port = $Port; pid = [int]$running.ProcessId; identity_verified = $true }; return
            }
            $launcher.Refresh()
            if ($launcher.HasExited -and $launcher.ExitCode -ne 0) { throw 'MYSQL_START_FAILED: inspect the independent data directory log; no database was initialized or force-stopped.' }
        }
        throw 'MYSQL_START_TIMEOUT: startup did not become ready; inspect the independent instance, which was not force-stopped.'
    }
    if ($null -eq $running) {
        if (@(Get-DataProcesses).Count -ne 0) { throw 'MYSQL_NOT_LISTENING: a configured mysqld process is still alive; no force stop was attempted.' }
        [pscustomobject]@{ status = 'ALREADY_STOPPED'; port = $Port; identity_verified = $false }; return
    }
    Assert-ServerIdentity
    $verifiedPid = [int]$running.ProcessId
    $verifiedCreation = $running.CreationDate
    $again = Get-VerifiedListener
    if ($null -eq $again -or [int]$again.ProcessId -ne $verifiedPid -or $again.CreationDate -ne $verifiedCreation) { throw 'MYSQL_IDENTITY_CHANGED: refusing shutdown after a process change.' }
    [void](Invoke-PrivateClient $admin @('--shutdown-timeout=15', 'shutdown') 20)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (@(Get-Listeners).Count -eq 0 -and @(Get-DataProcesses).Count -eq 0) {
            [pscustomobject]@{ status = 'STOPPED'; port = $Port; pid = $verifiedPid; identity_verified = $true }; return
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'MYSQL_STOP_TIMEOUT: graceful shutdown has not completed; mysqld was not force-killed.'
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
    # Do not persist credentials in process state or environment variables.
    $script:settings.Password = ''
    $password = ''
}

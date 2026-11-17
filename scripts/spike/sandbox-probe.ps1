#Requires -Version 7.4
<#
.SYNOPSIS
    WO-S.3 spike: probe every SandboxRunner.Control and the two-phase Maven flow against the local Docker engine.
    PowerShell port of scripts/spike/sandbox-probe.sh for Docker Desktop on Windows (Git Bash rewrites the paths).

.DESCRIPTION
    Same probes, verdicts and output format as the bash script: "RESULT <control> <status> <evidence>" lines,
    <OutDir>/results.tsv, <OutDir>/logs/*.log, and a markdown table with -Summary. Statuses: enforced,
    unenforceable, error (probe broke) for controls; pass, fail, skipped, info for Maven steps.
    Needs PowerShell 7.4+ (byte-preserving native pipes, Standard native argument passing) and docker on PATH.
    Exit: 0 when every probe ran and the MVP offline flow passed, 1 otherwise, 2 on usage/engine problems.

.EXAMPLE
    pwsh scripts/spike/sandbox-probe.ps1
    pwsh scripts/spike/sandbox-probe.ps1 -Group isolation,limits -Summary
#>
[CmdletBinding()]
param(
    [ValidateSet('isolation', 'limits', 'maven', 'all')]
    [string[]] $Group = @('all'),
    [switch] $Fresh,
    [switch] $Summary,
    [string] $Image = ($env:SECPIPE_SPIKE_IMAGE ?? 'secpipe-runner:spike'),
    [string] $MvpApp = ($env:SECPIPE_SPIKE_MVP_APP ??
        (Join-Path $PSScriptRoot '..' '..' '..' 'capstone-mvp-1' 'samples' 'vulnerable-java-app')),
    [string] $Benchmark = ($env:SECPIPE_SPIKE_BENCHMARK ?? ''),
    [string] $OutDir = ($env:SECPIPE_SPIKE_OUT ?? (Join-Path ([IO.Path]::GetTempPath()) 'secpipe-spike-s3')),
    [string] $Platform = ($env:SECPIPE_SPIKE_PLATFORM ??
        "$([Runtime.InteropServices.RuntimeInformation]::OSDescription) (pwsh $($PSVersionTable.PSVersion))")
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Continue'
$PSNativeCommandArgumentPassing = 'Standard'
$PSNativeCommandUseErrorActionPreference = $false

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..' '..')).Path
$Results = Join-Path $OutDir 'results.tsv'
$Logs = Join-Path $OutDir 'logs'
$RunId = "s3ps-$PID-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$Label = @('--label', "secpipe.spike.run=$RunId")
$script:Fatal = $false
$script:Out = ''
$script:RC = 0
$script:Work = ''
$script:EnvFile = ''
$Utf8 = [Text.UTF8Encoding]::new($false)   # no BOM: a BOM in --env-file would corrupt the first key
$DefaultLimits = @('--cpus', '2', '--memory', '2048m', '--memory-swap', '2048m', '--pids-limit', '512',
    '--ulimit', 'fsize=268435456:268435456')

function Stop-Usage([string] $Problem, [string] $Hint) {
    [Console]::Error.WriteLine("sandbox-probe: $Problem")
    [Console]::Error.WriteLine("hint: $Hint")
    exit 2
}

# Here-strings carry CRLF when git checks this file out with autocrlf on Windows; bash in the container needs LF.
function Lf([string] $Text) { return ($Text -replace "`r", '') }

function Write-TextLf([string] $Path, [string] $Text) { [IO.File]::WriteAllText($Path, (Lf $Text), $Utf8) }

function Write-Annotation([string] $Level, [string] $Title, [string] $Message) {
    if ($env:GITHUB_ACTIONS -ne 'true') { return }
    Write-Host "::${Level} title=${Title}::$($Message -replace '%', '%25')"
}

function Add-Result([string] $Grp, [string] $Name, [string] $Status, [string] $Evidence) {
    $ev = ($Evidence -replace "[`t`r`n]", ' ' -replace ' {2,}', ' ').Trim()
    if ($ev.Length -gt 700) { $ev = $ev.Substring(0, 700) }
    [IO.File]::AppendAllText($Results, "$Grp`t$Name`t$Status`t$ev`n", $Utf8)
    Write-Host "RESULT $Name $Status $ev"
    if ($Status -in 'error', 'fail') { Write-Annotation 'error' $Name "$Status - $ev" }
    else { Write-Annotation 'notice' $Name "$Status - $ev" }
}

function Invoke-Logged([string] $Name, [string[]] $Cmd) {
    $log = Join-Path $Logs "$Name.log"
    $header = "`n== $Name ==`n`$ $($Cmd -join ' ')"
    Add-Content -LiteralPath $log -Value $header
    Write-Host $header
    $rest = @($Cmd | Select-Object -Skip 1)
    $lines = & $Cmd[0] @rest 2>&1 | ForEach-Object { "$_" }
    $script:RC = $LASTEXITCODE
    $script:Out = (@($lines) -join "`n")
    $body = "$($script:Out)`n[exit $($script:RC)]"
    Add-Content -LiteralPath $log -Value $body
    Write-Host $body
}

function Get-Field([string] $Key, [string] $Text) {
    foreach ($line in ($Text -split "`n")) {
        if ($line.StartsWith("$Key=")) { return $line.Substring($Key.Length + 1).TrimEnd("`r") }
    }
    return ''
}

function Get-Lines([string] $Text, [string] $Pattern) {
    return @(($Text -split "`n") | Where-Object { $_ -match $Pattern })
}

# The hardened run (ADR-03): every control on, defaults from SandboxRunner.Limits.defaults().
function Get-SbxArgs {
    param([string[]] $Extra = @(), [string[]] $Limits = $DefaultLimits, [string] $User = '10001:10001',
        [string] $Net = 'none')
    return @('docker', 'run', '--rm') + $Label + @('--read-only',
        '--tmpfs', '/tmp:rw,noexec,nosuid,nodev,size=64m',
        '--tmpfs', '/home/sandbox:rw,noexec,nosuid,nodev,size=64m,uid=10001,gid=10001,mode=0700',
        '--user', $User, '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
        '--env-file', $script:EnvFile, '--network', $Net) + $Limits + $Extra + @($Image)
}

function New-ScratchDir([string] $Name) {
    $d = Join-Path $script:Work $Name
    New-Item -ItemType Directory -Force -Path $d | Out-Null
    if (-not $IsWindows) { chmod 0777 $d }
    return $d
}

function Get-HostUid([string] $Path) {
    if ($IsWindows) { return 'n/a (NTFS)' }
    $parts = ((ls -n $Path 2>$null) -split '\s+')
    if ($parts.Count -ge 3) { return $parts[2] } else { return '?' }
}

function Get-SizeKiB([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return 0 }
    $sum = (Get-ChildItem -LiteralPath $Path -Recurse -File -Force -ErrorAction SilentlyContinue |
        Measure-Object -Property Length -Sum).Sum
    return [long](($sum ?? 0) / 1KB)
}

# ---- isolation controls ---------------------------------------------------------------------------------------
function Test-NetworkNone {
    $url = 'https://repo.maven.apache.org/maven2/'
    Invoke-Logged 'NETWORK_NONE-positive-control' (@('docker', 'run', '--rm') + $Label + @($Image,
        'curl', '-sS', '-o', '/dev/null', '-w', 'http_code=%{http_code}', '--max-time', '15', $url))
    $pos = (($script:Out -split "`n") | Select-Object -Last 1)
    $sh = Lf @'
echo "interfaces=$(ls /sys/class/net | tr "\n" " ")"
curl -sS -o /dev/null --max-time 15 "$1"; echo "curl_exit=$?"
'@
    Invoke-Logged 'NETWORK_NONE' ((Get-SbxArgs) + @('bash', '-c', $sh, '_', $url))
    $ifs = (Get-Field 'interfaces' $script:Out).Trim()
    $crc = Get-Field 'curl_exit' $script:Out
    $cerr = (Get-Lines $script:Out '^curl:' | Select-Object -First 1)
    if ($crc -and $crc -ne '0' -and $ifs -eq 'lo') {
        Add-Result isolation NETWORK_NONE enforced ("--network none: curl to Maven Central exit $crc ($cerr); " +
            "interfaces: $ifs. Control run with default network: $pos")
    } elseif ($crc -eq '0') {
        Add-Result isolation NETWORK_NONE unenforceable "curl succeeded under --network none (interfaces: $ifs)"
    } else {
        Add-Result isolation NETWORK_NONE error "probe did not run as expected (rc=$($script:RC), curl_exit=$crc)"
    }
}

function Test-ReadOnlyRoot {
    Invoke-Logged 'READ_ONLY_ROOT-positive-control' (@('docker', 'run', '--rm') + $Label + @('--user', '0:0',
        '--cap-drop', 'ALL', $Image, 'bash', '-c', 'touch /etc/.s3probe && echo writable-without-read-only'))
    $pos = $script:Out
    $sh = Lf @'
for p in / /etc /usr /var /opt/java /opt/maven /scratch; do
    if touch "$p/.s3probe" 2>/tmp/e; then echo "WRITABLE $p"
    else echo "denied $p: $(sed "s/^touch: //" /tmp/e)"; fi
done
'@
    Invoke-Logged 'READ_ONLY_ROOT' ((Get-SbxArgs -User '0:0') + @('bash', '-c', $sh))
    $denied = @(Get-Lines $script:Out 'Read-only file system').Count
    $writable = @(Get-Lines $script:Out '^WRITABLE')
    if ($denied -eq 7 -and $writable.Count -eq 0 -and $pos -match 'writable-without-read-only') {
        Add-Result isolation READ_ONLY_ROOT enforced ("--read-only: root got EROFS on 7/7 paths (/, /etc, /usr, " +
            "/var, /opt/java, /opt/maven, /scratch); without --read-only root can write /etc")
    } elseif ($writable.Count -gt 0) {
        Add-Result isolation READ_ONLY_ROOT unenforceable ($writable -join ' ')
    } else {
        Add-Result isolation READ_ONLY_ROOT error "unexpected output (rc=$($script:RC), EROFS count=$denied)"
    }
}

function Test-ReadOnlyToolchain {
    $d = New-ScratchDir 'toolchain'
    Write-TextLf (Join-Path $d 'probe.txt') "cached-artifact`n"
    $sh = Lf @'
echo "read=$(cat /cache/m2/probe.txt)"
touch /cache/m2/new 2>&1; echo "touch_exit=$?"
rm /cache/m2/probe.txt 2>&1; echo "rm_exit=$?"
'@
    Invoke-Logged 'READ_ONLY_TOOLCHAIN' ((Get-SbxArgs -User '0:0' -Extra @('-v', "${d}:/cache/m2:ro")) +
        @('bash', '-c', $sh))
    $o = $script:Out
    if ((Get-Field 'read' $o) -eq 'cached-artifact' -and (Get-Field 'touch_exit' $o) -ne '0' -and
        (Get-Field 'rm_exit' $o) -ne '0' -and (Test-Path (Join-Path $d 'probe.txt')) -and
        $o -match 'Read-only file system') {
        Add-Result isolation READ_ONLY_TOOLCHAIN enforced ("-v cache:/cache/m2:ro: root can read, create and " +
            "delete both get EROFS, host file intact; image toolchain is covered by --read-only")
    } elseif ((Get-Field 'touch_exit' $o) -eq '0') {
        Add-Result isolation READ_ONLY_TOOLCHAIN unenforceable 'write to the :ro bind mount succeeded'
    } else {
        Add-Result isolation READ_ONLY_TOOLCHAIN error "unexpected output (rc=$($script:RC))"
    }
}

function Test-ScratchOnlyWrites {
    $d = New-ScratchDir 'scratch writes'   # the space is deliberate: Windows profile paths often contain one
    $sh = Lf @'
for p in /scratch /tmp "$HOME"; do
    if echo x > "$p/w" 2>/dev/null; then echo "ok $p"; else echo "FAIL $p"; fi
done
for p in / /var/tmp /opt /etc /usr/local; do
    if (echo x > "$p/w") 2>/dev/null; then echo "LEAK $p"; else echo "denied $p"; fi
done
cp /bin/true /tmp/t && if /tmp/t 2>/dev/null; then echo "tmp_exec=allowed"; else echo "tmp_exec=blocked"; fi
cp /bin/true /scratch/t
if /scratch/t 2>/dev/null; then echo "scratch_exec=allowed"; else echo "scratch_exec=blocked"; fi
'@
    Invoke-Logged 'SCRATCH_ONLY_WRITES' ((Get-SbxArgs -Extra @('-v', "${d}:/scratch")) + @('bash', '-c', $sh))
    $o = $script:Out
    $ok = @(Get-Lines $o '^ok ').Count
    $denied = @(Get-Lines $o '^denied ').Count
    $leaks = @(Get-Lines $o '^LEAK')
    $owner = Get-HostUid (Join-Path $d 'w')
    if ($ok -eq 3 -and $denied -eq 5 -and (Get-Field 'tmp_exec' $o) -eq 'blocked' -and
        (Test-Path -LiteralPath (Join-Path $d 'w'))) {
        Add-Result isolation SCRATCH_ONLY_WRITES enforced ("writes succeed only in /scratch (rw bind, host path " +
            "with a space), /tmp and HOME (tmpfs, noexec: exec from /tmp blocked); 5/5 other paths denied; host " +
            "file owner uid $owner; scratch exec $(Get-Field 'scratch_exec' $o)")
    } elseif ($leaks.Count -gt 0) {
        Add-Result isolation SCRATCH_ONLY_WRITES unenforceable ($leaks -join ' ')
    } else {
        Add-Result isolation SCRATCH_ONLY_WRITES error "unexpected output (rc=$($script:RC), ok=$ok, denied=$denied)"
    }
}

function Test-EnvAllowlist {
    $canary = "leak-canary-$RunId"
    $env:SECPIPE_SPIKE_CANARY = $canary
    $imageKeys = @(docker image inspect -f '{{range .Config.Env}}{{println .}}{{end}}' $Image |
        ForEach-Object { ($_ -split '=', 2)[0] } | Where-Object { $_ })
    $fileKeys = @((Get-Content -LiteralPath $script:EnvFile) | ForEach-Object { ($_ -split '=', 2)[0] })
    $allowed = $imageKeys + $fileKeys + @('HOSTNAME', 'PWD', 'SHLVL', '_')
    Invoke-Logged 'ENV_ALLOWLIST' ((Get-SbxArgs) + @('env'))
    $seen = @(($script:Out -split "`n") | Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
        ForEach-Object { ($_ -split '=', 2)[0] } | Sort-Object -Unique)
    $unexpected = @($seen | Where-Object { $_ -notin $allowed })
    $bare = Join-Path $script:Work 'env-bare.list'
    Write-TextLf $bare "SECPIPE_SPIKE_CANARY`n"
    Invoke-Logged 'ENV_ALLOWLIST-bare-key-pitfall' (@('docker', 'run', '--rm') + $Label +
        @('--env-file', $bare, $Image, 'env'))
    $leaked = if ($script:Out -match [regex]::Escape($canary)) { 'yes' } else { 'no' }
    Remove-Item Env:SECPIPE_SPIKE_CANARY
    if ($seen -contains 'SECPIPE_ALLOWED' -and $seen -notcontains 'SECPIPE_SPIKE_CANARY' -and
        $unexpected.Count -eq 0) {
        Add-Result isolation ENV_ALLOWLIST enforced ("--env-file with KEY=VALUE lines only: container env = " +
            "image ENV + allowlist + HOSTNAME; host canary absent ($($seen -join ' ')). Pitfall: a bare KEY " +
            "line copies the host value (leaked=$leaked)")
    } elseif ($seen -contains 'SECPIPE_SPIKE_CANARY') {
        Add-Result isolation ENV_ALLOWLIST unenforceable 'host canary visible in container'
    } else {
        Add-Result isolation ENV_ALLOWLIST error "unexpected keys: $($unexpected -join ' ') (rc=$($script:RC))"
    }
}

function Test-NonRootUser {
    $d = New-ScratchDir 'uid'
    $sh = Lf @'
echo "ids=$(id -u):$(id -g):$(id -G)"; grep "^Uid:" /proc/self/status; touch /scratch/owned
'@
    Invoke-Logged 'NON_ROOT_USER' ((Get-SbxArgs -Extra @('-v', "${d}:/scratch")) + @('bash', '-c', $sh))
    $ids = Get-Field 'ids' $script:Out
    $owner = Get-HostUid (Join-Path $d 'owned')
    if ($ids -eq '10001:10001:10001') {
        Add-Result isolation NON_ROOT_USER enforced ("--user 10001:10001: uid/gid/groups = $ids; file written " +
            "to scratch is owned by host uid $owner")
    } elseif ($ids -like '0:*') {
        Add-Result isolation NON_ROOT_USER unenforceable 'process ran as root'
    } else {
        Add-Result isolation NON_ROOT_USER error "unexpected output (rc=$($script:RC))"
    }
}

function Test-NoNewPrivileges {
    Invoke-Logged 'NO_NEW_PRIVILEGES' ((Get-SbxArgs) + @('grep', '-E', '^(NoNewPrivs|CapPrm|CapEff|CapBnd|CapAmb):',
        '/proc/self/status'))
    $st = @{}
    foreach ($line in ($script:Out -split "`n")) {
        $p = $line -split '\s+'
        if ($p.Count -ge 2) { $st[$p[0].TrimEnd(':')] = $p[1] }
    }
    Invoke-Logged 'NO_NEW_PRIVILEGES-root-chown' ((Get-SbxArgs -User '0:0') + @('bash', '-c',
        'touch /tmp/c; chown 1:1 /tmp/c 2>&1; echo "chown_exit=$?"'))
    $chown = Get-Field 'chown_exit' $script:Out
    $zero = '0000000000000000'
    if ($st['NoNewPrivs'] -eq '1' -and $st['CapEff'] -eq $zero -and $st['CapPrm'] -eq $zero -and
        $st['CapBnd'] -eq $zero -and $chown -and $chown -ne '0') {
        Add-Result isolation NO_NEW_PRIVILEGES enforced ("--security-opt no-new-privileges + --cap-drop ALL: " +
            "NoNewPrivs=1, CapPrm/CapEff/CapBnd=0; root in the container cannot chown (exit $chown)")
    } elseif ($st.ContainsKey('NoNewPrivs')) {
        Add-Result isolation NO_NEW_PRIVILEGES unenforceable ("NoNewPrivs=$($st['NoNewPrivs']) " +
            "CapEff=$($st['CapEff']) chown_exit=$chown")
    } else {
        Add-Result isolation NO_NEW_PRIVILEGES error "could not read /proc/self/status (rc=$($script:RC))"
    }
}

# ---- resource limits ------------------------------------------------------------------------------------------
function Test-CpuLimit {
    $sh = Lf @'
v1=/sys/fs/cgroup/cpu
q=$(cat /sys/fs/cgroup/cpu.max 2>/dev/null || echo "v1 $(cat $v1/cpu.cfs_quota_us) $(cat $v1/cpu.cfs_period_us)")
echo "cpu_max=$q"
u0=$(sed -n "s/^usage_usec //p" /sys/fs/cgroup/cpu.stat 2>/dev/null); s0=$(date +%s%N)
for i in 1 2; do timeout 4 bash -c "while :; do :; done" & done; wait
u1=$(sed -n "s/^usage_usec //p" /sys/fs/cgroup/cpu.stat 2>/dev/null); s1=$(date +%s%N)
echo "cpu_used_usec=$(( ${u1:-0} - ${u0:-0} ))"; echo "wall_usec=$(( (s1 - s0) / 1000 ))"
'@
    Invoke-Logged 'CPU_LIMIT' ((Get-SbxArgs -Limits @('--cpus', '0.5', '--memory', '512m', '--memory-swap', '512m',
        '--pids-limit', '64')) + @('bash', '-c', $sh))
    $q = Get-Field 'cpu_max' $script:Out
    $used = [double]((Get-Field 'cpu_used_usec' $script:Out) -as [double] ?? 0)
    $wall = [double]((Get-Field 'wall_usec' $script:Out) -as [double] ?? 0)
    $ratio = if ($wall -gt 0) { [math]::Round($used / $wall, 2) } else { -1 }
    if ($q -in '50000 100000', 'v1 50000 100000' -and $ratio -le 0.65) {
        Add-Result limits CPU_LIMIT enforced "--cpus 0.5: cgroup cpu.max '$q'; two busy loops for 4 s used $ratio CPUs"
    } elseif ($q) {
        Add-Result limits CPU_LIMIT unenforceable "cpu.max '$q', measured $ratio CPUs with --cpus 0.5"
    } else {
        Add-Result limits CPU_LIMIT error "could not read the cgroup cpu quota (rc=$($script:RC))"
    }
}

function Test-MemoryLimit {
    $sh = Lf @'
echo "memory_max=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || cat /sys/fs/cgroup/memory/memory.limit_in_bytes)"
head -c 1G /dev/zero | tail > /dev/null; echo "pipe_exit=$?"
echo "oom_kill=$(sed -n "s/^oom_kill //p" /sys/fs/cgroup/memory.events 2>/dev/null)"
'@
    Invoke-Logged 'MEMORY_LIMIT' ((Get-SbxArgs -Limits @('--cpus', '1', '--memory', '128m', '--memory-swap', '128m',
        '--pids-limit', '64')) + @('bash', '-c', $sh))
    $max = Get-Field 'memory_max' $script:Out
    $pe = Get-Field 'pipe_exit' $script:Out
    $oom = (Get-Field 'oom_kill' $script:Out) -as [int] ?? 0
    if ($max -eq '134217728' -and ($pe -eq '137' -or $oom -ge 1 -or $script:RC -eq 137)) {
        Add-Result limits MEMORY_LIMIT enforced ("--memory 128m --memory-swap 128m: memory.max=$max; buffering " +
            "1 GiB was OOM-killed (exit $pe, oom_kill=$oom, docker run exit $($script:RC))")
    } elseif ($max) {
        Add-Result limits MEMORY_LIMIT unenforceable "memory.max=$max, allocation exit $pe, oom_kill=$oom"
    } else {
        Add-Result limits MEMORY_LIMIT error "could not read memory.max (rc=$($script:RC))"
    }
}

function Test-PidLimit {
    $n = "$RunId-pids"
    Invoke-Logged 'PID_LIMIT-start' (@('docker', 'run', '-d', '--name', $n) + $Label + @('--read-only',
        '--tmpfs', '/tmp:rw,noexec,nosuid,size=16m', '--user', '10001:10001', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--network', 'none', '--cpus', '1', '--memory', '256m',
        '--memory-swap', '256m', '--pids-limit', '64', $Image, 'bash', '-c',
        '( bomb() { bomb | bomb & }; bomb ) >/dev/null 2>&1 & exec sleep 30'))
    Start-Sleep -Seconds 8
    $pids = (docker stats --no-stream --format '{{.PIDs}}' $n 2>$null) -as [int]
    $limit = docker inspect -f '{{.HostConfig.PidsLimit}}' $n 2>$null
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Logged 'PID_LIMIT-host-still-works' (@('docker', 'run', '--rm') + $Label + @('--network', 'none', $Image,
        'echo', 'host-ok'))
    $hostRc = $script:RC
    $secs = [int]$sw.Elapsed.TotalSeconds
    docker kill $n 2>&1 | Out-Null
    docker rm -f $n 2>&1 | Out-Null
    Write-Host "fork bomb: PIDs in container=$pids, PidsLimit=$limit, second container rc=$hostRc in ${secs}s"
    if ($limit -eq '64' -and $null -ne $pids -and $pids -le 64 -and $pids -ge 10 -and $hostRc -eq 0) {
        Add-Result limits PID_LIMIT enforced ("--pids-limit 64: fork bomb held at $pids PIDs; a second container " +
            "still started in $secs s; bomb killed with docker kill")
    } elseif ($null -ne $pids -and $pids -gt 64) {
        Add-Result limits PID_LIMIT unenforceable "fork bomb reached $pids PIDs with --pids-limit 64"
    } else {
        Add-Result limits PID_LIMIT error "inconclusive: PIDs=$pids PidsLimit=$limit host_rc=$hostRc"
    }
}

function Test-FileSizeLimit {
    $d = New-ScratchDir 'fsize'
    $sh = Lf @'
echo "ulimit_f_kib=$(ulimit -f)"
dd if=/dev/zero of=/scratch/big bs=1M count=32 2>/dev/null; echo "dd_exit=$?"
echo "size=$(stat -c %s /scratch/big)"
'@
    Invoke-Logged 'FILE_SIZE_LIMIT' ((Get-SbxArgs -Extra @('-v', "${d}:/scratch") -Limits @('--cpus', '1',
        '--memory', '256m', '--memory-swap', '256m', '--pids-limit', '64', '--ulimit', 'fsize=10485760:10485760')) +
        @('bash', '-c', $sh))
    $size = Get-Field 'size' $script:Out
    $dd = Get-Field 'dd_exit' $script:Out
    $big = Join-Path $d 'big'
    $hostSize = if (Test-Path -LiteralPath $big) { (Get-Item -LiteralPath $big).Length } else { '?' }
    if ($size -eq '10485760' -and $dd -ne '0') {
        Add-Result limits FILE_SIZE_LIMIT enforced ("--ulimit fsize=10485760 (bytes; ulimit -f shows " +
            "$(Get-Field 'ulimit_f_kib' $script:Out) KiB): 32 MiB write stopped at 10 MiB, dd exit $dd (SIGXFSZ); " +
            "host file size $hostSize")
    } elseif ($size -match '^\d+$') {
        Add-Result limits FILE_SIZE_LIMIT unenforceable "wrote $size bytes with a 10 MiB fsize limit"
    } else {
        Add-Result limits FILE_SIZE_LIMIT error "unexpected output (rc=$($script:RC))"
    }
}

function Test-DiskLimit {
    $sh = Lf @'
dd if=/dev/zero of=/var/tmp/fill bs=1M count=100 2>/dev/null; echo "dd_exit=$?"
echo "size=$(stat -c %s /var/tmp/fill)"
'@
    Invoke-Logged 'DISK_LIMIT-native-storage-opt' (@('docker', 'run', '--rm') + $Label + @('--user', '0:0',
        '--network', 'none', '--storage-opt', 'size=64m', $Image, 'bash', '-c', $sh))
    $native = $false
    if ($script:RC -eq 0 -and (Get-Field 'dd_exit' $script:Out) -ne '0') {
        $native = $true
        $nativeEv = "--storage-opt size=64m works: 100 MiB write stopped at $(Get-Field 'size' $script:Out) bytes"
    } elseif ($script:RC -eq 0) {
        $nativeEv = '--storage-opt size=64m accepted but NOT enforced (100 MiB write to the rootfs succeeded)'
    } else {
        $first = (($script:Out -split "`n") | Select-Object -First 1) ?? ''
        $nativeEv = "--storage-opt size=64m not usable: $($first.Substring(0, [math]::Min(200, $first.Length)))"
    }
    $in = New-ScratchDir 'disk-in'
    Write-TextLf (Join-Path $in 'in.txt') "scratch-in`n"
    $outTar = Join-Path $script:Work 'disk-out.tar'
    $errFile = Join-Path $script:Work 'disk-err.txt'
    $inner = Lf @'
tar -xf - -C /scratch >&2
dd if=/dev/zero of=/scratch/fill bs=1M count=100 2>/dev/null; echo "dd_exit=$?" >&2
echo "fill_bytes=$(stat -c %s /scratch/fill)" >&2
echo "memory_current_kib=$(( $(cat /sys/fs/cgroup/memory.current 2>/dev/null || echo 0) / 1024 ))" >&2
rm -f /scratch/fill; echo promoted > /scratch/out.txt
tar -cf - -C /scratch in.txt out.txt
'@
    $dargs = @('run', '-i', '--rm') + $Label + @('--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,nodev,size=16m',
        '--tmpfs', '/scratch:rw,nosuid,nodev,size=64m,uid=10001,gid=10001,mode=0700', '--user', '10001:10001',
        '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--network', 'none', '--cpus', '1',
        '--memory', '512m', '--memory-swap', '512m', '--pids-limit', '64', $Image, 'bash', '-c', $inner)
    Write-Host "`n== DISK_LIMIT-tmpfs-scratch ==`n`$ tar -cf - in.txt | docker $($dargs -join ' ') > disk-out.tar"
    # PowerShell 7.4 keeps bytes intact for native | native pipes and native > file redirection.
    tar -C $in -cf - in.txt | docker @dargs 2> $errFile > $outTar
    $o = (Get-Content -LiteralPath $errFile -Raw) ?? ''
    Write-Host $o
    Add-Content -LiteralPath (Join-Path $Logs 'DISK_LIMIT-tmpfs-scratch.log') -Value $o
    $fill = (Get-Field 'fill_bytes' $o) -as [long]
    $dd = Get-Field 'dd_exit' $o
    $rt = ((tar -xOf $outTar out.txt 2>$null) ?? '').Trim()
    if ($native) {
        Add-Result limits DISK_LIMIT enforced "native: $nativeEv"
    } elseif ($null -ne $fill -and $fill -le 67108864 -and $dd -ne '0' -and $rt -eq 'promoted') {
        Add-Result limits DISK_LIMIT enforced ("emulated: $nativeEv. Size-capped tmpfs scratch (size=64m) stopped " +
            "a 100 MiB write at $fill bytes (ENOSPC); tmpfs counts against --memory (memory.current " +
            "$(Get-Field 'memory_current_kib' $o) KiB); files go in and out as a tar stream on stdin/stdout")
    } else {
        Add-Result limits DISK_LIMIT unenforceable "$nativeEv; tmpfs emulation: fill=$fill dd_exit=$dd roundtrip='$rt'"
    }
}

function Test-WallClockLimit {
    $n = "$RunId-clock"
    $argv = @('run', '--name', $n) + $Label + @('--read-only', '--user', '10001:10001', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--network', 'none', $Image, 'sleep', '120')
    Write-Host "`n== WALL_CLOCK_LIMIT ==`n`$ docker $($argv -join ' ')  (killed after 5 s)"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $psi = [Diagnostics.ProcessStartInfo]::new('docker')
    foreach ($a in $argv) { $psi.ArgumentList.Add($a) }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $proc = [Diagnostics.Process]::Start($psi)
    $timedOut = -not $proc.WaitForExit(5000)
    if ($timedOut) { $proc.Kill($true); $proc.WaitForExit() }
    $afterCli = docker inspect -f '{{.State.Running}}' $n 2>$null
    docker kill $n 2>&1 | Out-Null
    $afterKill = docker inspect -f '{{.State.Running}}' $n 2>$null
    docker rm -f $n 2>&1 | Out-Null
    $secs = [int]$sw.Elapsed.TotalSeconds
    Write-Host ("timed_out=$timedOut running_after_cli_killed=$afterCli " +
        "running_after_docker_kill=$afterKill elapsed=${secs}s")
    if ($timedOut -and $afterKill -eq 'false' -and $secs -lt 30) {
        Add-Result limits WALL_CLOCK_LIMIT enforced ("5 s host timer + docker kill stopped 'sleep 120' after " +
            "$secs s. Killing only the docker CLI left the container running=$afterCli, so docker kill <name> " +
            "is required")
    } elseif ($afterKill -eq 'true') {
        Add-Result limits WALL_CLOCK_LIMIT unenforceable 'container still running after docker kill'
    } else {
        Add-Result limits WALL_CLOCK_LIMIT error "inconclusive: timed_out=$timedOut running_after_kill=$afterKill"
    }
}

# ---- two-phase Maven ------------------------------------------------------------------------------------------
$SpikeTest = @'
package com.example.vulnlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.Socket;
import org.junit.jupiter.api.Test;

class CryptoUtilSpikeTest {

    @Test
    void md5OfPasswordIsTheKnownWeakDigest() throws Exception {
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", new CryptoUtil().hashPassword("password"));
    }

    @Test
    void sha256ChecksumOfEmptyInput() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                new CryptoUtil().checksum(new byte[0]));
    }

    @Test
    void generatedTestHasNoNetwork() {
        assertThrows(IOException.class, () -> new Socket("repo.maven.apache.org", 443).close());
    }
}
'@

function Invoke-MavenFlow([string] $Name, [string] $Src, [bool] $Junit, [bool] $FatalOnFail) {
    if (-not (Test-Path -LiteralPath (Join-Path $Src 'pom.xml'))) {
        Add-Result maven $Name skipped "no pom.xml at $Src"
        if ($FatalOnFail) { $script:Fatal = $true }
        return
    }
    $ext = @(Get-ChildItem -LiteralPath $Src -Recurse -Filter 'pom.xml' -File |
        Select-String -SimpleMatch '<extensions>true</extensions>' -List | ForEach-Object { $_.Path })
    if (Test-Path -LiteralPath (Join-Path $Src '.mvn' 'extensions.xml')) { $ext += '.mvn/extensions.xml' }
    if ($ext.Count -gt 0) {
        Add-Result maven "$Name-preflight" info ("build extensions found ($($ext -join ' ')): ADR-03 pre-flight " +
            "would refuse without --allow-build-extensions; the spike continues")
    }
    $base = New-ScratchDir "maven-$Name"
    $scratch = Join-Path $base 'scratch'
    $cache = Join-Path $base 'cache'
    $project = Join-Path $scratch 'project'
    New-Item -ItemType Directory -Force -Path $project, $cache | Out-Null
    Get-ChildItem -LiteralPath $Src -Force | Where-Object { $_.Name -notin '.git', 'target' } |
        Copy-Item -Destination $project -Recurse -Force
    if ($Junit) {
        $td = Join-Path $scratch 'spike-test' 'com' 'example' 'vulnlab'
        New-Item -ItemType Directory -Force -Path $td | Out-Null
        Write-TextLf (Join-Path $td 'CryptoUtilSpikeTest.java') $SpikeTest
    }
    if (-not $IsWindows) { chmod -R a+rwX $scratch $cache }

    $sw = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Logged "$Name-phase1-prefetch" ((Get-SbxArgs -Net 'bridge' -Extra @('-v', "${scratch}:/scratch",
        '-v', "${cache}:/cache/m2", '-w', '/scratch/project')) + @('mvn', '-B', '-q', '-Dmaven.repo.local=/cache/m2',
        'dependency:go-offline', 'dependency:resolve-plugins'))
    $secs = [int]$sw.Elapsed.TotalSeconds
    if ($script:RC -eq 0) {
        Add-Result maven "$Name-phase1-prefetch" pass ("mvn -B -q dependency:go-offline dependency:resolve-plugins " +
            "-Dmaven.repo.local=/cache/m2 (network on) in $secs s; cache $(Get-SizeKiB $cache) KiB")
    } else {
        Add-Result maven "$Name-phase1-prefetch" fail ("exit $($script:RC) after $secs s: " +
            ((Get-Lines $script:Out 'ERROR' | Select-Object -First 2) -join ' '))
        if ($FatalOnFail) { $script:Fatal = $true }
        return
    }

    $sw.Restart()
    Invoke-Logged "$Name-phase2-offline" ((Get-SbxArgs -Extra @('-v', "${scratch}:/scratch",
        '-v', "${cache}:/cache/m2:ro", '-w', '/scratch/project')) + @('mvn', '-o', '-B', '-q',
        '-Dmaven.repo.local=/scratch/m2', '-Dmaven.repo.local.tail=/cache/m2', 'compile',
        'dependency:build-classpath', '-Dmdep.outputFile=/scratch/classpath.txt'))
    $secs = [int]$sw.Elapsed.TotalSeconds
    $cpFile = Join-Path $scratch 'classpath.txt'
    if ($script:RC -eq 0 -and (Test-Path -LiteralPath $cpFile)) {
        $cpCount = @(((Get-Content -LiteralPath $cpFile -Raw) -split ':') | Where-Object { $_.Trim() }).Count
        Add-Result maven "$Name-phase2-offline" pass ("--network none: mvn -o compile dependency:build-classpath " +
            "with -Dmaven.repo.local.tail=/cache/m2 (ro mount) in $secs s; $cpCount classpath entries; /scratch/m2 " +
            "holds $(Get-SizeKiB (Join-Path $scratch 'm2')) KiB")
    } else {
        Add-Result maven "$Name-phase2-offline" fail ("exit $($script:RC): " +
            ((Get-Lines $script:Out 'ERROR' | Select-Object -First 2) -join ' '))
        if ($FatalOnFail) { $script:Fatal = $true }
        return
    }
    if (-not $Junit) { return }

    $sh = Lf @'
set -e
cp=$(cat /scratch/classpath.txt)
mkdir -p /scratch/spike-test-classes
javac -d /scratch/spike-test-classes -cp "/scratch/project/target/classes:$cp:$JUNIT_CONSOLE_JAR" \
    $(find /scratch/spike-test -name "*.java")
java -jar "$JUNIT_CONSOLE_JAR" execute --disable-banner --details=tree --fail-if-no-tests \
    -cp "/scratch/spike-test-classes:/scratch/project/target/classes:$cp" \
    --select-class com.example.vulnlab.CryptoUtilSpikeTest
'@
    Invoke-Logged "$Name-junit5-offline" ((Get-SbxArgs -Extra @('-v', "${scratch}:/scratch", '-w', '/scratch')) +
        @('bash', '-c', $sh))
    $status = (Get-Lines $script:Out '\[ +\d+ tests (successful|failed) +\]' |
        ForEach-Object { $_ -replace ' +', ' ' }) -join ' '
    if ($script:RC -eq 0 -and $script:Out -match '\[ +3 tests successful +\]') {
        Add-Result maven "$Name-junit5-offline" pass ("--network none: javac + junit-platform-console-standalone " +
            "against build-classpath, target POM untouched: $status")
    } else {
        Add-Result maven "$Name-junit5-offline" fail "exit $($script:RC): $status"
        if ($FatalOnFail) { $script:Fatal = $true }
    }
}

# ---- engine facts, summary, main ------------------------------------------------------------------------------
function Add-EngineFacts {
    if ((Test-Path -LiteralPath $Results) -and (Select-String -LiteralPath $Results -Pattern '^engine\t' -Quiet)) {
        return
    }
    $server = docker version --format '{{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}' 2>$null
    $info = docker info --format 'cgroup v{{.CgroupVersion}} ({{.CgroupDriver}}), storage {{.Driver}}' 2>$null
    $sec = docker info --format '{{.SecurityOptions}}' 2>$null
    Add-Result engine ENGINE info "$Platform; docker $server; $info, security $sec"
}

function Show-Summary {
    if (-not (Test-Path -LiteralPath $Results)) {
        Stop-Usage "no results at $Results" 'run the probe first (pwsh scripts/spike/sandbox-probe.ps1)'
    }
    $md = [Text.StringBuilder]::new()
    [void]$md.AppendLine("## WO-S.3 sandbox probe: $Platform").AppendLine()
    [void]$md.AppendLine('| Group | Control / step | Result | Evidence |').AppendLine('|---|---|---|---|')
    foreach ($line in [IO.File]::ReadAllLines($Results)) {
        $f = $line -split "`t", 4
        if ($f.Count -lt 4) { continue }
        [void]$md.AppendLine("| $($f[0]) | ``$($f[1])`` | **$($f[2])** | $($f[3] -replace '\|', '\|') |")
    }
    Write-Host $md.ToString()
    if ($env:GITHUB_STEP_SUMMARY) { [IO.File]::AppendAllText($env:GITHUB_STEP_SUMMARY, $md.ToString() + "`n", $Utf8) }
}

function Remove-Work {
    foreach ($id in @(docker ps -aq --filter "label=secpipe.spike.run=$RunId" 2>$null)) {
        if ($id) { docker rm -f $id 2>&1 | Out-Null }
    }
    if ($script:Work -and (Test-Path -LiteralPath $script:Work)) {
        # Files written by uid 10001 cannot always be removed by the host user; delete them from a container.
        docker run --rm --user 0:0 --network none -v "$($script:Work):/w" $Image find /w -mindepth 1 -delete 2>&1 |
            Out-Null
        Remove-Item -LiteralPath $script:Work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Any terminating error (including Set-StrictMode violations) fails the run instead of silently skipping probes.
trap {
    [Console]::Error.WriteLine("sandbox-probe: unexpected error: $_")
    Write-Annotation 'error' 'sandbox-probe.ps1' "unexpected error: $_"
    Remove-Work
    exit 1
}
New-Item -ItemType Directory -Force -Path $Logs | Out-Null
if ($Summary -and -not $PSBoundParameters.ContainsKey('Group')) { Show-Summary; exit 0 }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Stop-Usage 'docker not found on PATH' 'install Docker Desktop (WSL 2 backend) and reopen the terminal'
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { Stop-Usage 'the Docker engine is not reachable' 'start Docker Desktop, then rerun' }
docker image inspect $Image *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "building $Image from spikes/sandbox"
    docker build -t $Image (Join-Path $RepoRoot 'spikes' 'sandbox')
    if ($LASTEXITCODE -ne 0) { Stop-Usage 'docker build failed' 'see the build log above' }
}
if ($Fresh -or $Group -contains 'all') { [IO.File]::WriteAllText($Results, '', $Utf8) }
$script:Work = Join-Path ([IO.Path]::GetTempPath()) "secpipe-s3 $RunId"   # the space is deliberate (path quirk probe)
New-Item -ItemType Directory -Force -Path $script:Work | Out-Null
if (-not $IsWindows) { chmod 0755 $script:Work }
$script:EnvFile = Join-Path $script:Work 'env.list'   # the allowlist: KEY=VALUE lines only, LF, no BOM
Write-TextLf $script:EnvFile "HOME=/home/sandbox`nLANG=C.UTF-8`nSECPIPE_ALLOWED=yes`n"
try {
    Add-EngineFacts
    $all = $Group -contains 'all'
    if ($all -or $Group -contains 'isolation') {
        Test-NetworkNone; Test-ReadOnlyRoot; Test-ReadOnlyToolchain; Test-ScratchOnlyWrites
        Test-EnvAllowlist; Test-NonRootUser; Test-NoNewPrivileges
    }
    if ($all -or $Group -contains 'limits') {
        Test-CpuLimit; Test-MemoryLimit; Test-PidLimit; Test-FileSizeLimit; Test-DiskLimit; Test-WallClockLimit
    }
    if ($all -or $Group -contains 'maven') {
        Invoke-MavenFlow 'mvp-vulnerable-java-app' $MvpApp $true $true
        if ($Benchmark) { Invoke-MavenFlow 'owasp-benchmark' $Benchmark $false $false }
        else { Add-Result maven owasp-benchmark skipped 'SECPIPE_SPIKE_BENCHMARK not set' }
    }
} finally {
    Remove-Work
}
if (Select-String -LiteralPath $Results -Pattern "`terror`t" -Quiet) { $script:Fatal = $true }
if ($Summary) { Show-Summary }
exit ([int]$script:Fatal)

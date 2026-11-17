#!/usr/bin/env bash
# WO-S.3 spike: probe every SandboxRunner.Control and the two-phase Maven flow against the local Docker engine.
#
# Usage: bash scripts/spike/sandbox-probe.sh [--fresh] [--group isolation|limits|maven|all]... [--summary]
#   --group    which probes to run (repeatable; default: all). isolation = 7 controls, limits = 6 controls,
#              maven = prefetch -> offline compile -> JUnit 5 on the MVP sample (+ OWASP Benchmark if given).
#   --fresh    clear results.tsv first (implied when every group runs).
#   --summary  print the markdown control table from results.tsv (and append it to $GITHUB_STEP_SUMMARY).
# Env:
#   SECPIPE_SPIKE_IMAGE      runner image (default secpipe-runner:spike; built from spikes/sandbox if missing)
#   SECPIPE_SPIKE_MVP_APP    MVP vulnerable-java-app dir (default ../capstone-mvp-1/samples/vulnerable-java-app)
#   SECPIPE_SPIKE_BENCHMARK  OWASP BenchmarkJava checkout (optional; skipped when unset)
#   SECPIPE_SPIKE_OUT        results and logs (default ${TMPDIR:-/tmp}/secpipe-spike-s3)
#   SECPIPE_SPIKE_PLATFORM   label for the summary (default: uname -s/-m)
# Output: "RESULT <control> <status> <evidence>" lines, $OUT/results.tsv, $OUT/logs/*.log. Statuses: enforced,
# unenforceable, error (probe itself broke) for controls; pass, fail, skipped, info for Maven steps.
# Under GitHub Actions each result is also a ::notice (or ::error) annotation titled with the control name.
# Exit: 0 when every probe ran and the MVP offline flow passed, 1 otherwise, 2 on usage/engine problems.
# Host needs: docker, bash 3.2+ (macOS default works), mktemp, sed, grep, awk, tar. No GNU coreutils needed.
set -uo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
IMAGE="${SECPIPE_SPIKE_IMAGE:-secpipe-runner:spike}"
MVP_APP="${SECPIPE_SPIKE_MVP_APP:-$REPO_ROOT/../capstone-mvp-1/samples/vulnerable-java-app}"
BENCHMARK="${SECPIPE_SPIKE_BENCHMARK:-}"
OUT="${SECPIPE_SPIKE_OUT:-${TMPDIR:-/tmp}/secpipe-spike-s3}"
PLATFORM="${SECPIPE_SPIKE_PLATFORM:-$(uname -s)/$(uname -m)}"
RESULTS="$OUT/results.tsv"
LOGS="$OUT/logs"
RUN_ID="s3-$$-$(date +%s)"
LABEL=(--label "secpipe.spike.run=$RUN_ID")
FATAL=0
TAB=$(printf '\t')
WORK=""
OUTPUT=""
RC=0

die() { printf 'sandbox-probe: %s\nhint: %s\n' "$1" "$2" >&2; exit 2; }

usage() { sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

annotate() { # level title message  (GitHub workflow command; % must be escaped, message is single-line)
    [[ "${GITHUB_ACTIONS:-}" == "true" ]] || return 0
    printf '::%s title=%s::%s\n' "$1" "$2" "$(printf '%s' "$3" | sed 's/%/%25/g')"
}

record() { # group name status evidence... (evidence words are joined, so long strings may be split)
    local ev
    ev=$(printf '%s' "${*:4}" | tr '\t\r\n' '   ' | sed -e 's/  */ /g' -e 's/^ //' | cut -c1-700)
    printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$ev" >> "$RESULTS"
    printf 'RESULT %s %s %s\n' "$2" "$3" "$ev"
    case "$3" in
        error|fail) annotate error "$2" "$3 - $ev" ;;
        *) annotate notice "$2" "$3 - $ev" ;;
    esac
}

capture() { # name cmd... -> OUTPUT, RC; echoes the command and its output into the job log and $LOGS/<name>.log
    local name=$1 log
    shift
    log="$LOGS/$name.log"
    printf '\n== %s ==\n$ %s\n' "$name" "$*" | tee -a "$log"
    OUTPUT=$("$@" 2>&1)
    RC=$?
    printf '%s\n[exit %s]\n' "$OUTPUT" "$RC" | tee -a "$log"
}

field() { # key text -> value of the first "key=value" line
    printf '%s\n' "$2" | sed -n "s/^$1=//p" | head -1
}

# Portable wall clock (macOS has no GNU timeout): TERM after <secs>, KILL 3 s later. Returns 124 when it fired.
with_timeout() { # secs cmd...
    local secs=$1 pid watcher rc marker
    shift
    marker="$WORK/timeout.$RANDOM$RANDOM"
    "$@" &
    pid=$!
    ( sleep "$secs"; : > "$marker"; kill -TERM "$pid" 2>/dev/null; sleep 3; kill -KILL "$pid" 2>/dev/null ) \
        >/dev/null 2>&1 &
    watcher=$!
    wait "$pid"
    rc=$?
    kill "$watcher" 2>/dev/null
    wait "$watcher" 2>/dev/null
    if [[ -e "$marker" ]]; then rm -f "$marker"; return 124; fi
    return "$rc"
}

# ---- the hardened run (ADR-03): every control on, defaults from SandboxRunner.Limits.defaults() ----------------
# Probes override one piece at a time through these dynamically scoped variables (bash `local` is dynamic).
SBX_USER=10001:10001
SBX_NET=none
SBX_LIMITS=(--cpus 2 --memory 2048m --memory-swap 2048m --pids-limit 512 --ulimit fsize=268435456:268435456)

sbx() { # [docker options...] -- command...   (runs $IMAGE with every control unless a probe overrides one)
    local -a extra
    extra=()
    while [[ $# -gt 0 && "$1" != "--" ]]; do extra+=("$1"); shift; done
    [[ $# -gt 0 ]] && shift
    docker run --rm "${LABEL[@]}" --read-only \
        --tmpfs /tmp:rw,noexec,nosuid,nodev,size=64m \
        --tmpfs /home/sandbox:rw,noexec,nosuid,nodev,size=64m,uid=10001,gid=10001,mode=0700 \
        --user "$SBX_USER" --cap-drop ALL --security-opt no-new-privileges \
        --env-file "$ENV_FILE" --network "$SBX_NET" "${SBX_LIMITS[@]}" \
        ${extra[@]+"${extra[@]}"} "$IMAGE" "$@"
}

new_dir() { # name -> path of a fresh host dir under $WORK that uid 10001 can write (bind-mount scratch/cache)
    local d="$WORK/$1"
    mkdir -p "$d" && chmod 0777 "$d" && printf '%s' "$d"
}

host_uid() { ls -n "$1" 2>/dev/null | awk 'NR==1 {print $3}'; }

# ---- isolation controls ---------------------------------------------------------------------------------------
probe_network_none() {
    local url=https://repo.maven.apache.org/maven2/ pos ifs crc cerr
    capture NETWORK_NONE-positive-control docker run --rm "${LABEL[@]}" "$IMAGE" \
        curl -sS -o /dev/null -w 'http_code=%{http_code}' --max-time 15 "$url"
    pos=$(printf '%s' "$OUTPUT" | tail -1)
    capture NETWORK_NONE sbx -- bash -c \
        'echo "interfaces=$(ls /sys/class/net | tr "\n" " ")"
         curl -sS -o /dev/null --max-time 15 "$1"; echo "curl_exit=$?"' \
        _ "$url"
    ifs=$(field interfaces "$OUTPUT" | sed 's/ *$//')
    crc=$(field curl_exit "$OUTPUT")
    cerr=$(printf '%s\n' "$OUTPUT" | grep '^curl:' | head -1)
    if [[ -n "$crc" && "$crc" != 0 && "$ifs" == "lo" ]]; then
        record isolation NETWORK_NONE enforced \
            "--network none: curl to Maven Central exit $crc ($cerr); interfaces: $ifs. Control run with default "\
            "network: $pos"
    elif [[ "$crc" == 0 ]]; then
        record isolation NETWORK_NONE unenforceable "curl succeeded under --network none (interfaces: $ifs)"
    else
        record isolation NETWORK_NONE error "probe did not run as expected (rc=$RC, curl_exit=$crc, interfaces=$ifs)"
    fi
}

probe_read_only_root() {
    local script='for p in / /etc /usr /var /opt/java /opt/maven /scratch; do
        if touch "$p/.s3probe" 2>/tmp/e; then echo "WRITABLE $p"
        else echo "denied $p: $(sed "s/^touch: //" /tmp/e)"; fi
    done'
    local pos denied
    # Positive control: same command as root without --read-only must be able to write /etc.
    capture READ_ONLY_ROOT-positive-control docker run --rm "${LABEL[@]}" --user 0:0 --cap-drop ALL "$IMAGE" \
        bash -c 'touch /etc/.s3probe && echo writable-without-read-only'
    pos=$OUTPUT
    local SBX_USER=0:0   # root inside, so only the read-only mount (not file permissions) can stop the write
    capture READ_ONLY_ROOT sbx -- bash -c "$script"
    denied=$(printf '%s\n' "$OUTPUT" | grep -c 'Read-only file system')
    if [[ "$denied" -eq 7 ]] && ! printf '%s' "$OUTPUT" | grep -q WRITABLE \
        && [[ "$pos" == *writable-without-read-only* ]]; then
        record isolation READ_ONLY_ROOT enforced \
            "--read-only: root got EROFS on 7/7 paths (/, /etc, /usr, /var, /opt/java, /opt/maven, /scratch); "\
            "without --read-only root can write /etc"
    elif printf '%s' "$OUTPUT" | grep -q WRITABLE; then
        record isolation READ_ONLY_ROOT unenforceable "$(printf '%s\n' "$OUTPUT" | grep WRITABLE | tr '\n' ' ')"
    else
        record isolation READ_ONLY_ROOT error "unexpected output (rc=$RC, EROFS count=$denied, positive control: $pos)"
    fi
}

probe_read_only_toolchain() {
    local d
    d=$(new_dir toolchain)
    printf 'cached-artifact\n' > "$d/probe.txt"
    local SBX_USER=0:0
    capture READ_ONLY_TOOLCHAIN sbx -v "$d:/cache/m2:ro" -- bash -c \
        'echo "read=$(cat /cache/m2/probe.txt)"
         touch /cache/m2/new 2>&1; echo "touch_exit=$?"
         rm /cache/m2/probe.txt 2>&1; echo "rm_exit=$?"'
    if [[ "$(field read "$OUTPUT")" == cached-artifact && "$(field touch_exit "$OUTPUT")" != 0 \
        && "$(field rm_exit "$OUTPUT")" != 0 && -f "$d/probe.txt" ]] \
        && printf '%s' "$OUTPUT" | grep -q 'Read-only file system'; then
        record isolation READ_ONLY_TOOLCHAIN enforced \
            "-v cache:/cache/m2:ro: root can read, create and delete both get EROFS, host file intact; image "\
            "toolchain (/opt/java, /opt/maven) is covered by --read-only"
    elif [[ "$(field touch_exit "$OUTPUT")" == 0 ]]; then
        record isolation READ_ONLY_TOOLCHAIN unenforceable "write to the :ro bind mount succeeded"
    else
        record isolation READ_ONLY_TOOLCHAIN error "unexpected output (rc=$RC)"
    fi
}

probe_scratch_only_writes() {
    local d ok denied owner
    d=$(new_dir scratch-writes)
    capture SCRATCH_ONLY_WRITES sbx -v "$d:/scratch" -- bash -c '
        for p in /scratch /tmp "$HOME"; do
            if echo x > "$p/w" 2>/dev/null; then echo "ok $p"; else echo "FAIL $p"; fi
        done
        for p in / /var/tmp /opt /etc /usr/local; do
            if (echo x > "$p/w") 2>/dev/null; then echo "LEAK $p"; else echo "denied $p"; fi
        done
        cp /bin/true /tmp/t && if /tmp/t 2>/dev/null; then echo "tmp_exec=allowed"; else echo "tmp_exec=blocked"; fi
        cp /bin/true /scratch/t
        if /scratch/t 2>/dev/null; then echo "scratch_exec=allowed"; else echo "scratch_exec=blocked"; fi'
    ok=$(printf '%s\n' "$OUTPUT" | grep -c '^ok ')
    denied=$(printf '%s\n' "$OUTPUT" | grep -c '^denied ')
    owner=$(host_uid "$d/w")
    if [[ "$ok" -eq 3 && "$denied" -eq 5 && "$(field tmp_exec "$OUTPUT")" == blocked && -f "$d/w" ]]; then
        record isolation SCRATCH_ONLY_WRITES enforced \
            "writes succeed only in /scratch (rw bind), /tmp and HOME (tmpfs, noexec: exec from /tmp blocked); 5/5 "\
            "other paths denied; host sees scratch file owned by uid $owner; scratch exec "\
            "$(field scratch_exec "$OUTPUT") (bind mounts cannot be noexec)"
    elif printf '%s' "$OUTPUT" | grep -q '^LEAK'; then
        record isolation SCRATCH_ONLY_WRITES unenforceable "$(printf '%s\n' "$OUTPUT" | grep '^LEAK' | tr '\n' ' ')"
    else
        record isolation SCRATCH_ONLY_WRITES error "unexpected output (rc=$RC, ok=$ok, denied=$denied, "\
            "tmp_exec=$(field tmp_exec "$OUTPUT"))"
    fi
}

probe_env_allowlist() {
    local canary="leak-canary-$RUN_ID" image_keys allowed seen unexpected bare_file leaked
    export SECPIPE_SPIKE_CANARY="$canary"
    image_keys=$(docker image inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$IMAGE" | sed 's/=.*//')
    allowed="$WORK/allowed-keys"
    { printf '%s\n' "$image_keys"; sed 's/=.*//' "$ENV_FILE"; printf '%s\n' HOSTNAME PWD SHLVL _; } > "$allowed"
    capture ENV_ALLOWLIST sbx -- env
    seen=$(printf '%s\n' "$OUTPUT" | sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' | sort -u)
    unexpected=$(printf '%s\n' "$seen" | grep -Fxv -f "$allowed" | tr '\n' ' ')
    # Pitfall check: a bare KEY line (no =) in --env-file copies the HOST value into the container.
    bare_file="$WORK/env-bare.list"
    printf 'SECPIPE_SPIKE_CANARY\n' > "$bare_file"
    capture ENV_ALLOWLIST-bare-key-pitfall docker run --rm "${LABEL[@]}" --env-file "$bare_file" "$IMAGE" env
    if printf '%s' "$OUTPUT" | grep -q "$canary"; then leaked=yes; else leaked=no; fi
    unset SECPIPE_SPIKE_CANARY
    if [[ "$seen" == *SECPIPE_ALLOWED* && "$seen" != *SECPIPE_SPIKE_CANARY* && -z "${unexpected// /}" ]]; then
        record isolation ENV_ALLOWLIST enforced \
            "--env-file with KEY=VALUE lines only: container env = image ENV + allowlist + HOSTNAME/PWD/SHLVL; "\
            "host canary absent ($(printf '%s' "$seen" | tr '\n' ' ')). Pitfall: a bare KEY line copies the host "\
            "value (leaked=$leaked)"
    elif [[ "$seen" == *SECPIPE_SPIKE_CANARY* ]]; then
        record isolation ENV_ALLOWLIST unenforceable "host canary visible in container"
    else
        record isolation ENV_ALLOWLIST error "unexpected keys: $unexpected (rc=$RC)"
    fi
}

probe_non_root_user() {
    local d owner
    d=$(new_dir uid)
    capture NON_ROOT_USER sbx -v "$d:/scratch" -- bash -c \
        'echo "ids=$(id -u):$(id -g):$(id -G)"; grep "^Uid:" /proc/self/status; touch /scratch/owned'
    owner=$(host_uid "$d/owned")
    if [[ "$(field ids "$OUTPUT")" == "10001:10001:10001" ]]; then
        record isolation NON_ROOT_USER enforced \
            "--user 10001:10001: uid/gid/groups = $(field ids "$OUTPUT"); file written to scratch is owned by host "\
            "uid ${owner:-?}"
    elif [[ "$(field ids "$OUTPUT")" == 0:* ]]; then
        record isolation NON_ROOT_USER unenforceable "process ran as root"
    else
        record isolation NON_ROOT_USER error "unexpected output (rc=$RC)"
    fi
}

probe_no_new_privileges() {
    local nnp capeff capbnd capprm chown_rc
    capture NO_NEW_PRIVILEGES sbx -- grep -E '^(NoNewPrivs|CapPrm|CapEff|CapBnd|CapAmb):' /proc/self/status
    nnp=$(printf '%s\n' "$OUTPUT" | awk '$1=="NoNewPrivs:" {print $2}')
    capeff=$(printf '%s\n' "$OUTPUT" | awk '$1=="CapEff:" {print $2}')
    capprm=$(printf '%s\n' "$OUTPUT" | awk '$1=="CapPrm:" {print $2}')
    capbnd=$(printf '%s\n' "$OUTPUT" | awk '$1=="CapBnd:" {print $2}')
    local SBX_USER=0:0   # even root inside has no capabilities with --cap-drop ALL
    capture NO_NEW_PRIVILEGES-root-chown sbx -- bash -c 'touch /tmp/c; chown 1:1 /tmp/c 2>&1; echo "chown_exit=$?"'
    chown_rc=$(field chown_exit "$OUTPUT")
    if [[ "$nnp" == 1 && "$capeff" == 0000000000000000 && "$capprm" == 0000000000000000 \
        && "$capbnd" == 0000000000000000 && -n "$chown_rc" && "$chown_rc" != 0 ]]; then
        record isolation NO_NEW_PRIVILEGES enforced \
            "--security-opt no-new-privileges + --cap-drop ALL: NoNewPrivs=1, CapPrm/CapEff/CapBnd=0; root in the "\
            "container cannot chown (exit $chown_rc)"
    elif [[ -n "$nnp" ]]; then
        record isolation NO_NEW_PRIVILEGES unenforceable "NoNewPrivs=$nnp CapEff=$capeff CapBnd=$capbnd "\
            "chown_exit=$chown_rc"
    else
        record isolation NO_NEW_PRIVILEGES error "could not read /proc/self/status (rc=$RC)"
    fi
}

# ---- resource limits ------------------------------------------------------------------------------------------
probe_cpu_limit() {
    local -a SBX_LIMITS
    SBX_LIMITS=(--cpus 0.5 --memory 512m --memory-swap 512m --pids-limit 64)
    capture CPU_LIMIT sbx -- bash -c '
        v1=/sys/fs/cgroup/cpu
        q=$(cat /sys/fs/cgroup/cpu.max 2>/dev/null \
            || echo "v1 $(cat $v1/cpu.cfs_quota_us) $(cat $v1/cpu.cfs_period_us)")
        echo "cpu_max=$q"
        u0=$(sed -n "s/^usage_usec //p" /sys/fs/cgroup/cpu.stat 2>/dev/null); s0=$(date +%s%N)
        for i in 1 2; do timeout 4 bash -c "while :; do :; done" & done; wait
        u1=$(sed -n "s/^usage_usec //p" /sys/fs/cgroup/cpu.stat 2>/dev/null); s1=$(date +%s%N)
        echo "cpu_used_usec=$(( ${u1:-0} - ${u0:-0} ))"; echo "wall_usec=$(( (s1 - s0) / 1000 ))"'
    local q used wall ratio
    q=$(field cpu_max "$OUTPUT")
    used=$(field cpu_used_usec "$OUTPUT")
    wall=$(field wall_usec "$OUTPUT")
    ratio=$(awk -v u="${used:-0}" -v w="${wall:-0}" 'BEGIN { if (w > 0) printf "%.2f", u / w; else print "n/a" }')
    if [[ "$q" == "50000 100000" || "$q" == "v1 50000 100000" ]] \
        && awk -v r="$ratio" 'BEGIN { exit !(r == "n/a" || r + 0 <= 0.65) }'; then
        record limits CPU_LIMIT enforced \
            "--cpus 0.5: cgroup cpu.max '$q'; two busy loops for 4 s used $ratio CPUs on average"
    elif [[ -n "$q" ]]; then
        record limits CPU_LIMIT unenforceable "cpu.max '$q', measured $ratio CPUs with --cpus 0.5"
    else
        record limits CPU_LIMIT error "could not read the cgroup cpu quota (rc=$RC)"
    fi
}

probe_memory_limit() {
    local -a SBX_LIMITS
    SBX_LIMITS=(--cpus 1 --memory 128m --memory-swap 128m --pids-limit 64)
    capture MEMORY_LIMIT sbx -- bash -c '
        echo "memory_max=$(cat /sys/fs/cgroup/memory.max 2>/dev/null \
            || cat /sys/fs/cgroup/memory/memory.limit_in_bytes)"
        head -c 1G /dev/zero | tail > /dev/null; echo "pipe_exit=$?"
        echo "oom_kill=$(sed -n "s/^oom_kill //p" /sys/fs/cgroup/memory.events 2>/dev/null)"'
    local max pe oom
    max=$(field memory_max "$OUTPUT")
    pe=$(field pipe_exit "$OUTPUT")
    oom=$(field oom_kill "$OUTPUT")
    # cgroup v2 may OOM-kill the whole container (memory.oom.group), so docker run itself exiting 137 also counts.
    if [[ "$max" == 134217728 && ( "$pe" == 137 || "${oom:-0}" -ge 1 || "$RC" == 137 ) ]]; then
        record limits MEMORY_LIMIT enforced \
            "--memory 128m --memory-swap 128m: memory.max=$max; buffering 1 GiB was OOM-killed (exit $pe, "\
            "oom_kill=${oom:-n/a}, docker run exit $RC)"
    elif [[ -n "$max" ]]; then
        record limits MEMORY_LIMIT unenforceable "memory.max=$max, allocation exit $pe, oom_kill=${oom:-n/a}"
    else
        record limits MEMORY_LIMIT error "could not read memory.max (rc=$RC)"
    fi
}

probe_pid_limit() {
    local n="$RUN_ID-pids" pids limit host_rc t0 t1
    capture PID_LIMIT-start docker run -d --name "$n" "${LABEL[@]}" --read-only --tmpfs /tmp:rw,noexec,nosuid,size=16m \
        --user 10001:10001 --cap-drop ALL --security-opt no-new-privileges --network none \
        --cpus 1 --memory 256m --memory-swap 256m --pids-limit 64 "$IMAGE" \
        bash -c '( bomb() { bomb | bomb & }; bomb ) >/dev/null 2>&1 & exec sleep 30'
    sleep 8
    pids=$(docker stats --no-stream --format '{{.PIDs}}' "$n" 2>/dev/null)
    limit=$(docker inspect -f '{{.HostConfig.PidsLimit}}' "$n" 2>/dev/null)
    t0=$(date +%s)
    capture PID_LIMIT-host-still-works docker run --rm "${LABEL[@]}" --network none "$IMAGE" echo host-ok
    host_rc=$RC
    t1=$(date +%s)
    printf 'fork bomb: PIDs in container=%s, PidsLimit=%s, second container rc=%s in %ss\n' \
        "$pids" "$limit" "$host_rc" "$((t1 - t0))" | tee -a "$LOGS/PID_LIMIT.log"
    docker kill "$n" >/dev/null 2>&1
    docker rm -f "$n" >/dev/null 2>&1
    if [[ "$limit" == 64 && "$pids" =~ ^[0-9]+$ && "$pids" -le 64 && "$pids" -ge 10 && "$host_rc" == 0 ]]; then
        record limits PID_LIMIT enforced \
            "--pids-limit 64: fork bomb held at $pids PIDs; a second container still started in $((t1 - t0)) s; "\
            "bomb killed with docker kill"
    elif [[ "$pids" =~ ^[0-9]+$ && "$pids" -gt 64 ]]; then
        record limits PID_LIMIT unenforceable "fork bomb reached $pids PIDs with --pids-limit 64"
    else
        record limits PID_LIMIT error "inconclusive: PIDs=$pids PidsLimit=$limit host_rc=$host_rc"
    fi
}

probe_file_size_limit() {
    local d host_size
    d=$(new_dir fsize)
    local -a SBX_LIMITS
    SBX_LIMITS=(--cpus 1 --memory 256m --memory-swap 256m --pids-limit 64 --ulimit fsize=10485760:10485760)
    capture FILE_SIZE_LIMIT sbx -v "$d:/scratch" -- bash -c '
        echo "ulimit_f_kib=$(ulimit -f)"
        dd if=/dev/zero of=/scratch/big bs=1M count=32 2>/dev/null; echo "dd_exit=$?"
        echo "size=$(stat -c %s /scratch/big)"'
    host_size=$(wc -c < "$d/big" 2>/dev/null | tr -d ' ')
    if [[ "$(field size "$OUTPUT")" == 10485760 && "$(field dd_exit "$OUTPUT")" != 0 ]]; then
        record limits FILE_SIZE_LIMIT enforced \
            "--ulimit fsize=10485760 (bytes; ulimit -f shows $(field ulimit_f_kib "$OUTPUT") KiB): 32 MiB write "\
            "stopped at 10 MiB, dd exit $(field dd_exit "$OUTPUT") (SIGXFSZ); host file size $host_size"
    elif [[ "$(field size "$OUTPUT")" =~ ^[0-9]+$ ]]; then
        record limits FILE_SIZE_LIMIT unenforceable "wrote $(field size "$OUTPUT") bytes with a 10 MiB fsize limit"
    else
        record limits FILE_SIZE_LIMIT error "unexpected output (rc=$RC)"
    fi
}

disk_tmpfs_roundtrip() { # stdin tar -> tmpfs scratch -> fill -> stdout tar (promotion without a host bind mount)
    tar -C "$WORK/disk-in" -cf - in.txt | docker run -i --rm "${LABEL[@]}" --read-only \
        --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m \
        --tmpfs /scratch:rw,nosuid,nodev,size=64m,uid=10001,gid=10001,mode=0700 \
        --user 10001:10001 --cap-drop ALL --security-opt no-new-privileges --network none \
        --cpus 1 --memory 512m --memory-swap 512m --pids-limit 64 "$IMAGE" bash -c '
        tar -xf - -C /scratch >&2
        dd if=/dev/zero of=/scratch/fill bs=1M count=100 2>/dev/null; echo "dd_exit=$?" >&2
        echo "fill_bytes=$(stat -c %s /scratch/fill)" >&2
        echo "memory_current_kib=$(( $(cat /sys/fs/cgroup/memory.current 2>/dev/null || echo 0) / 1024 ))" >&2
        rm -f /scratch/fill; echo promoted > /scratch/out.txt
        tar -cf - -C /scratch in.txt out.txt' > "$WORK/disk-out.tar"
}

probe_disk_limit() {
    local native_ev native=no fill dd_rc rt
    capture DISK_LIMIT-native-storage-opt docker run --rm "${LABEL[@]}" --user 0:0 --network none \
        --storage-opt size=64m "$IMAGE" bash -c \
        'dd if=/dev/zero of=/var/tmp/fill bs=1M count=100 2>/dev/null; echo "dd_exit=$?"
         echo "size=$(stat -c %s /var/tmp/fill)"'
    if [[ $RC -eq 0 && "$(field dd_exit "$OUTPUT")" != 0 ]]; then
        native=yes
        native_ev="--storage-opt size=64m works: 100 MiB write stopped at $(field size "$OUTPUT") bytes"
    elif [[ $RC -eq 0 ]]; then
        native_ev="--storage-opt size=64m accepted but NOT enforced (100 MiB write to the rootfs succeeded)"
    else
        native_ev="--storage-opt size=64m not usable: "\
            "$(printf '%s\n' "$OUTPUT" | grep -v '^\[exit' | head -1 | cut -c1-200)"
    fi
    mkdir -p "$WORK/disk-in"
    printf 'scratch-in\n' > "$WORK/disk-in/in.txt"
    capture DISK_LIMIT-tmpfs-scratch disk_tmpfs_roundtrip
    fill=$(field fill_bytes "$OUTPUT")
    dd_rc=$(field dd_exit "$OUTPUT")
    rt=$(tar -xOf "$WORK/disk-out.tar" out.txt 2>/dev/null)
    if [[ "$native" == yes ]]; then
        record limits DISK_LIMIT enforced "native: $native_ev"
    elif [[ "$fill" =~ ^[0-9]+$ && "$fill" -le 67108864 && "$dd_rc" != 0 && "$rt" == promoted ]]; then
        record limits DISK_LIMIT enforced \
            "emulated: $native_ev. Size-capped tmpfs scratch (size=64m) stopped a 100 MiB write at $fill bytes "\
            "(ENOSPC); tmpfs counts against --memory (memory.current $(field memory_current_kib "$OUTPUT") KiB); "\
            "files go in and out as a tar stream on stdin/stdout"
    else
        record limits DISK_LIMIT unenforceable "$native_ev; tmpfs emulation: fill=$fill dd_exit=$dd_rc roundtrip='$rt'"
    fi
}

probe_wall_clock_limit() {
    local n="$RUN_ID-clock" t0 t1 cli_rc running_after_cli running_after_kill
    t0=$(date +%s)
    capture WALL_CLOCK_LIMIT with_timeout 5 docker run --name "$n" "${LABEL[@]}" --read-only --user 10001:10001 \
        --cap-drop ALL --security-opt no-new-privileges --network none "$IMAGE" sleep 120
    cli_rc=$RC
    running_after_cli=$(docker inspect -f '{{.State.Running}}' "$n" 2>/dev/null)
    docker kill "$n" >/dev/null 2>&1
    running_after_kill=$(docker inspect -f '{{.State.Running}}' "$n" 2>/dev/null)
    docker rm -f "$n" >/dev/null 2>&1
    t1=$(date +%s)
    printf 'cli_rc=%s running_after_cli_killed=%s running_after_docker_kill=%s elapsed=%ss\n' \
        "$cli_rc" "$running_after_cli" "$running_after_kill" "$((t1 - t0))" | tee -a "$LOGS/WALL_CLOCK_LIMIT.log"
    if [[ "$cli_rc" == 124 && "$running_after_kill" == false && $((t1 - t0)) -lt 30 ]]; then
        record limits WALL_CLOCK_LIMIT enforced \
            "5 s host timer + docker kill stopped 'sleep 120' after $((t1 - t0)) s. Killing only the docker CLI "\
            "left the container running=$running_after_cli, so docker kill <name> is required"
    elif [[ "$running_after_kill" == true ]]; then
        record limits WALL_CLOCK_LIMIT unenforceable "container still running after docker kill"
    else
        record limits WALL_CLOCK_LIMIT error "inconclusive: cli_rc=$cli_rc running_after_kill=$running_after_kill"
    fi
}

# ---- two-phase Maven --------------------------------------------------------------------------------------------
write_spike_test() { # dir
    mkdir -p "$1/com/example/vulnlab"
    cat > "$1/com/example/vulnlab/CryptoUtilSpikeTest.java" <<'JAVA'
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
JAVA
}

offline_diagnose() { # label scratch cache: is the missing artifact in the prefetch cache, and does -llr help?
    local label=$1 scratch=$2 cache=$3 coord g a v dir found tracking
    coord=$(printf '%s\n' "$OUTPUT" | sed -n 's/.*dependency: \([^ ]*\) (.*/\1/p' | head -1)
    if [[ -n "$coord" ]]; then
        g=$(printf '%s' "$coord" | cut -d: -f1 | tr '.' '/')
        a=$(printf '%s' "$coord" | cut -d: -f2)
        v=$(printf '%s' "$coord" | cut -d: -f4)
        dir="$cache/$g/$a/$v"
        found=$(ls "$dir" 2>/dev/null | tr '\n' ' ')
        tracking=$(grep -v '^#' "$dir/_remote.repositories" 2>/dev/null | tr '\n' ' ')
        record maven "$label-offline-diagnosis" info \
            "missing $coord; in prefetch cache: ${found:-NOT PRESENT}; _remote.repositories: ${tracking:-none}"
    fi
    capture "$label-phase2-offline-llr" with_timeout 1800 sbx -v "$scratch:/scratch" -v "$cache:/cache/m2:ro" \
        -w /scratch/project -- mvn -o -B -q -llr -Dmaven.repo.local=/scratch/m2 -Dmaven.repo.local.tail=/cache/m2 \
        compile dependency:build-classpath -Dmdep.outputFile=/scratch/classpath.txt
    if [[ $RC -eq 0 ]]; then
        record maven "$label-phase2-offline-llr" pass "same offline build with -llr (ignore _remote.repositories) passes"
    else
        record maven "$label-phase2-offline-llr" info \
            "with -llr still fails: exit $RC: $(printf '%s\n' "$OUTPUT" | grep -m2 'ERROR' | tr '\n' ' ')"
    fi
}

maven_flow() { # label source-dir run-junit(yes|no) fatal-on-fail(yes|no)
    local label=$1 src=$2 junit=$3 fatal=$4 base scratch cache ext t0 t1 cp_count status
    if [[ ! -f "$src/pom.xml" ]]; then
        record maven "$label" skipped "no pom.xml at $src"
        [[ "$fatal" == yes ]] && FATAL=1
        return
    fi
    ext=$(cd "$src" && { grep -rl --include=pom.xml '<extensions>true</extensions>' . 2>/dev/null; \
        [[ -f .mvn/extensions.xml ]] && echo .mvn/extensions.xml; } | tr '\n' ' ')
    if [[ -n "$ext" ]]; then
        record maven "$label-preflight" info "build extensions found ($ext): ADR-03 pre-flight would refuse "\
            "without --allow-build-extensions; the spike continues"
    fi
    base=$(new_dir "maven-$label")
    scratch="$base/scratch"
    cache="$base/cache"
    mkdir -p "$scratch/project" "$cache"
    cp -R "$src/." "$scratch/project/"
    rm -rf "$scratch/project/.git" "$scratch/project/target"
    [[ "$junit" == yes ]] && write_spike_test "$scratch/spike-test"
    chmod -R a+rwX "$scratch" "$cache"

    # Phase 1: prefetch. Network on, no secrets, every other control on; only scratch and the cache are mounted.
    t0=$(date +%s)
    local SBX_NET=bridge
    capture "$label-phase1-prefetch" with_timeout 1800 sbx -v "$scratch:/scratch" -v "$cache:/cache/m2" \
        -w /scratch/project -- mvn -B -q -Dmaven.repo.local=/cache/m2 dependency:go-offline dependency:resolve-plugins
    t1=$(date +%s)
    SBX_NET=none
    if [[ $RC -eq 0 ]]; then
        record maven "$label-phase1-prefetch" pass \
            "mvn -B -q dependency:go-offline dependency:resolve-plugins -Dmaven.repo.local=/cache/m2 (network on) "\
            "in $((t1 - t0)) s; cache $(du -sk "$cache" | awk '{print $1}') KiB"
    else
        record maven "$label-phase1-prefetch" fail "exit $RC after $((t1 - t0)) s: "\
            "$(printf '%s\n' "$OUTPUT" | grep -m2 'ERROR' | tr '\n' ' ')"
        [[ "$fatal" == yes ]] && FATAL=1
        return
    fi

    # Phase 2: offline. --network none, cache mounted read-only as the local-repo tail, writes go to /scratch/m2.
    t0=$(date +%s)
    capture "$label-phase2-offline" with_timeout 1800 sbx -v "$scratch:/scratch" -v "$cache:/cache/m2:ro" \
        -w /scratch/project -- mvn -o -B -q -Dmaven.repo.local=/scratch/m2 -Dmaven.repo.local.tail=/cache/m2 \
        compile dependency:build-classpath -Dmdep.outputFile=/scratch/classpath.txt
    t1=$(date +%s)
    if [[ $RC -eq 0 && -f "$scratch/classpath.txt" ]]; then
        cp_count=$(tr ':' '\n' < "$scratch/classpath.txt" | grep -c .)
        record maven "$label-phase2-offline" pass \
            "--network none: mvn -o compile dependency:build-classpath with -Dmaven.repo.local.tail=/cache/m2 (ro "\
            "mount) in $((t1 - t0)) s; $cp_count classpath entries; /scratch/m2 holds "\
            "$(du -sk "$scratch/m2" 2>/dev/null | awk '{print $1}') KiB"
    else
        record maven "$label-phase2-offline" fail "exit $RC: "\
            "$(printf '%s\n' "$OUTPUT" | grep -m2 'ERROR' | tr '\n' ' ')"
        [[ "$fatal" == yes ]] && FATAL=1
        offline_diagnose "$label" "$scratch" "$cache"
        return
    fi

    [[ "$junit" == yes ]] || return
    capture "$label-junit5-offline" with_timeout 600 sbx -v "$scratch:/scratch" -w /scratch -- bash -c '
        set -e
        cp=$(cat /scratch/classpath.txt)
        mkdir -p /scratch/spike-test-classes
        javac -d /scratch/spike-test-classes -cp "/scratch/project/target/classes:$cp:$JUNIT_CONSOLE_JAR" \
            $(find /scratch/spike-test -name "*.java")
        java -jar "$JUNIT_CONSOLE_JAR" execute --disable-banner --details=tree --fail-if-no-tests \
            -cp "/scratch/spike-test-classes:/scratch/project/target/classes:$cp" \
            --select-class com.example.vulnlab.CryptoUtilSpikeTest'
    status=$(printf '%s\n' "$OUTPUT" | grep -E '\[ +[0-9]+ tests (successful|failed) +\]' | tr -s ' ' | tr '\n' ' ')
    if [[ $RC -eq 0 ]] && printf '%s' "$OUTPUT" | grep -qE '\[ +3 tests successful +\]'; then
        record maven "$label-junit5-offline" pass \
            "--network none: javac + junit-platform-console-standalone against build-classpath, target POM "\
            "untouched: $status"
    else
        record maven "$label-junit5-offline" fail "exit $RC: $status "\
            "$(printf '%s\n' "$OUTPUT" | grep -m2 -E 'error|Exception' | tr '\n' ' ')"
        [[ "$fatal" == yes ]] && FATAL=1
    fi
}

# ---- engine facts, summary, main ---------------------------------------------------------------------------------
engine_facts() {
    grep -q "^engine${TAB}" "$RESULTS" 2>/dev/null && return
    local server info
    server=$(docker version --format '{{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}' 2>/dev/null)
    info=$(docker info --format 'cgroup v{{.CgroupVersion}} ({{.CgroupDriver}}), storage {{.Driver}}' 2>/dev/null)
    info="$info, security $(docker info --format '{{.SecurityOptions}}' 2>/dev/null)"
    record engine ENGINE info "$PLATFORM; docker $server; $info"
}

summary() {
    local out
    [[ -f "$RESULTS" ]] || die "no results at $RESULTS" "run the probe first (bash scripts/spike/sandbox-probe.sh)"
    out=$(
        printf '## WO-S.3 sandbox probe: %s\n\n' "$PLATFORM"
        printf '| Group | Control / step | Result | Evidence |\n|---|---|---|---|\n'
        awk -F'\t' '{ gsub(/\|/, "\\|", $4); printf "| %s | `%s` | **%s** | %s |\n", $1, $2, $3, $4 }' "$RESULTS"
    )
    printf '%s\n' "$out"
    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then printf '%s\n\n' "$out" >> "$GITHUB_STEP_SUMMARY"; fi
}

cleanup() {
    local ids id
    ids=$(docker ps -aq --filter "label=secpipe.spike.run=$RUN_ID" 2>/dev/null)
    for id in $ids; do docker rm -f "$id" >/dev/null 2>&1; done
    if [[ -n "$WORK" && -d "$WORK" ]]; then
        # Files written by uid 10001 cannot always be removed by the host user; delete them from a container.
        docker run --rm --user 0:0 --network none -v "$WORK:/w" "$IMAGE" find /w -mindepth 1 -delete >/dev/null 2>&1
        rm -rf "$WORK" 2>/dev/null
    fi
}

main() {
    local -a selected
    local fresh=0 want_summary=0 g
    selected=()
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --group) [[ $# -ge 2 ]] || die "--group needs a value" "use isolation, limits, maven or all"
                     selected+=("$2"); shift 2 ;;
            --fresh) fresh=1; shift ;;
            --summary) want_summary=1; shift ;;
            -h|--help) usage; exit 0 ;;
            *) die "unknown argument: $1" "run with --help" ;;
        esac
    done
    mkdir -p "$LOGS"
    if [[ ${#selected[@]} -eq 0 && $want_summary -eq 1 ]]; then summary; return 0; fi
    [[ ${#selected[@]} -eq 0 ]] && selected=(all)
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) die "Git Bash/MSYS rewrites /container/paths and C:\\ paths in docker arguments" \
            "on Windows run: pwsh scripts/spike/sandbox-probe.ps1" ;;
    esac
    command -v docker >/dev/null 2>&1 || die "docker not found on PATH" "install Docker Desktop or Docker Engine"
    docker info >/dev/null 2>&1 || die "the Docker engine is not reachable" "start Docker Desktop / dockerd, then rerun"
    if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
        echo "building $IMAGE from spikes/sandbox"
        docker build -t "$IMAGE" "$REPO_ROOT/spikes/sandbox" || die "docker build failed" "see the build log above"
    fi
    for g in "${selected[@]}"; do [[ "$g" == all ]] && fresh=1; done
    [[ $fresh -eq 1 ]] && : > "$RESULTS"
    WORK=$(mktemp -d "${TMPDIR:-/tmp}/secpipe-s3.XXXXXX") || die "mktemp failed" "check TMPDIR"
    chmod 0755 "$WORK"
    trap cleanup EXIT
    ENV_FILE="$WORK/env.list"   # the allowlist: KEY=VALUE lines only (a bare KEY would copy the host value)
    printf 'HOME=/home/sandbox\nLANG=C.UTF-8\nSECPIPE_ALLOWED=yes\n' > "$ENV_FILE"
    engine_facts
    for g in "${selected[@]}"; do
        case "$g" in
            isolation|limits|maven|all) ;;
            *) die "unknown group: $g" "use isolation, limits, maven or all" ;;
        esac
        if [[ "$g" == isolation || "$g" == all ]]; then
            probe_network_none; probe_read_only_root; probe_read_only_toolchain; probe_scratch_only_writes
            probe_env_allowlist; probe_non_root_user; probe_no_new_privileges
        fi
        if [[ "$g" == limits || "$g" == all ]]; then
            probe_cpu_limit; probe_memory_limit; probe_pid_limit; probe_file_size_limit; probe_disk_limit
            probe_wall_clock_limit
        fi
        if [[ "$g" == maven || "$g" == all ]]; then
            maven_flow mvp-vulnerable-java-app "$MVP_APP" yes yes
            if [[ -n "$BENCHMARK" ]]; then
                maven_flow owasp-benchmark "$BENCHMARK" no no
            else
                record maven owasp-benchmark skipped "SECPIPE_SPIKE_BENCHMARK not set"
            fi
        fi
    done
    if grep -q "${TAB}error${TAB}" "$RESULTS"; then FATAL=1; fi
    [[ $want_summary -eq 1 ]] && summary
    return "$FATAL"
}

main "$@"

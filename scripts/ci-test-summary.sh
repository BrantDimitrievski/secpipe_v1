#!/usr/bin/env bash
# Prints per-module and total test counts from Surefire/Failsafe XML reports (WO-0.3). Reports, never gates: exit 0.
# Usage: scripts/ci-test-summary.sh [--class PATTERN]   (PATTERN is a shell-style glob matched against class names)
set -euo pipefail
pattern=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --class) pattern="${2:?--class needs a PATTERN}"; shift 2 ;;
    -h|--help) echo "usage: ci-test-summary.sh [--class PATTERN]"; exit 0 ;;
    *) echo "ci-test-summary: unknown argument: $1" >&2; echo "hint: usage is ci-test-summary.sh [--class PATTERN]" >&2; exit 2 ;;
  esac
done
python3 -B - "$pattern" <<'PY'
import fnmatch
import os
import sys
import xml.etree.ElementTree as ET

pattern = sys.argv[1]
reports = []
for root, dirs, files in os.walk("."):
    dirs[:] = sorted(d for d in dirs if d not in (".git", "node_modules"))
    if os.path.basename(root) in ("surefire-reports", "failsafe-reports"):
        reports += [os.path.join(root, f) for f in sorted(files) if f.startswith("TEST-") and f.endswith(".xml")]

modules = {}
classes = {}
for path in sorted(reports):
    parts = os.path.normpath(path).split(os.sep)
    module = os.sep.join(parts[:-3]) or "."
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        print("warning: unreadable report " + path, file=sys.stderr)
        continue
    suites = [tree.getroot()] if tree.getroot().tag == "testsuite" else list(tree.getroot().iter("testsuite"))
    for s in suites:
        counts = [int(s.get(k, "0") or 0) for k in ("tests", "failures", "errors", "skipped")]
        name = s.get("name", os.path.basename(path))
        m = modules.setdefault(module, [0, 0, 0, 0])
        c = classes.setdefault(name, [0, 0])
        for i in range(4):
            m[i] += counts[i]
        c[0] += counts[0]
        c[1] += counts[3]

print("== test summary ==")
if not modules:
    print("no test reports found")
    sys.exit(0)
total = [0, 0, 0, 0]
for module in sorted(modules):
    t, f, e, s = modules[module]
    print(f"{module} tests={t} failures={f} errors={e} skipped={s}")
    for i in range(4):
        total[i] += modules[module][i]
for name in sorted(classes):
    if pattern and not fnmatch.fnmatch(name, pattern):
        continue
    print(f"  {name} tests={classes[name][0]} skipped={classes[name][1]}")
print(f"TOTAL tests={total[0]} failures={total[1]} errors={total[2]} skipped={total[3]}")
PY

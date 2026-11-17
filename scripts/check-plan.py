#!/usr/bin/env python3
"""Mechanical plan checks (used after every re-plan; see docs/WORKFLOW.md).

Usage: python3 scripts/check-plan.py [--dir docs/work-orders]
Checks: dependency ids exist; dependencies only on earlier waves; no cycles; owner != reviewer; no two work orders in
the same wave own overlapping paths; every FR-1..15, NFR-1..6 and feature A..S is traced by at least one work order.
Exit 1 on any error.
"""
import argparse
import itertools
import pathlib
import re
import sys


def parse(d):
    wos = {}
    for p in sorted(pathlib.Path(d).glob("WO-*.md")):
        t = p.read_text(encoding="utf-8")
        wid = re.match(r"^### (WO-[0-9A-Z]+\.\d+):", t).group(1)
        m = re.search(r"^Wave: (\d+)\s+Depends on: (.*?)\s+Blocks: (.*)$", t, re.M)
        tr = re.search(r"^Traces: (.*)$", t, re.M).group(1)
        ow = re.search(r"^Suggested human owner: (.*?) \(reviewer: (.*?)\)", t, re.M)
        own = re.search(r"Files you own \(create/modify — nothing else\):(.*?)\n\nFiles you may read", t, re.S)
        wos[wid] = {"wave": int(m.group(1)), "deps": re.findall(r"WO-[0-9A-Z]+\.\d+", m.group(2)), "traces": tr,
                    "owner": ow.group(1), "rev": ow.group(2),
                    "paths": [x for x in re.findall(r"`([^`]+)`", own.group(1)) if "/" in x and "..." not in x] if own else []}
    return wos


def expand(p):
    m = re.search(r"\{([^}]*)\}", p)
    if not m:
        return [p]
    return [x for alt in m.group(1).split(",") for x in expand(p[:m.start()] + alt + p[m.end():])]


def overlap(a, b):
    for x in expand(a):
        for y in expand(b):
            px, py = x.split("**")[0], y.split("**")[0]
            if x == y or (x.endswith("**") and y.startswith(px)) or (y.endswith("**") and x.startswith(py)):
                return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="docs/work-orders")
    wos = parse(ap.parse_args().dir)
    errors = []
    for w, d in wos.items():
        for dep in d["deps"]:
            if dep not in wos:
                errors.append(f"{w}: unknown dependency {dep}")
            elif wos[dep]["wave"] >= d["wave"]:
                errors.append(f"{w} (wave {d['wave']}) depends on {dep} (wave {wos[dep]['wave']})")
        if d["owner"] == d["rev"]:
            errors.append(f"{w}: owner reviews own work")
    state = {}

    def dfs(n, path):
        if state.get(n) == 1:
            errors.append("cycle: " + " -> ".join(path + [n]))
            return
        if state.get(n) == 2:
            return
        state[n] = 1
        for x in wos[n]["deps"]:
            if x in wos:
                dfs(x, path + [n])
        state[n] = 2
    for n in wos:
        dfs(n, [])
    waves = {}
    for w, d in wos.items():
        waves.setdefault(d["wave"], []).append(w)
    for wave, ids in waves.items():
        for x, y in itertools.combinations(sorted(ids), 2):
            for a in wos[x]["paths"]:
                for b in wos[y]["paths"]:
                    if overlap(a, b):
                        errors.append(f"wave {wave}: {x} and {y} both own {a} / {b}")
    traces = " ".join(d["traces"] for d in wos.values())
    for i in range(1, 16):
        if not re.search(rf"FR-{i}\b", traces) and "FR-1…FR-15" not in traces:
            errors.append(f"FR-{i} not traced")
    for i in range(1, 7):
        if not re.search(rf"NFR-{i}\b", traces):
            errors.append(f"NFR-{i} not traced")
    feats = " ".join(d["traces"].split("·", 1)[1] if "·" in d["traces"] else "" for d in wos.values())
    for c in "ABCDEFGHIJKLMNOPQRS":
        if not re.search(rf"\b{c}\b", feats) and "A–S" not in feats:
            errors.append(f"feature {c} not traced")
    for e in errors:
        print("ERROR", e)
    print(f"work_orders={len(wos)} waves={len(waves)} errors={len(errors)}")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()

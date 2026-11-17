#!/usr/bin/env python3
"""Split the SecPipe plan Markdown into repository files (Part 13, kit item 1).

Usage: python3 scripts/split-plan.py PLAN.md [--out DIR] [--force] [--dry-run]

Outputs (relative to --out, default "."):
  * every `File: <path>` + fenced block anywhere in the plan  -> <path>
  * docs/PLAN.md            <- preamble + Parts 1-5 and 8-12 (Part 4 keeps its File blocks inline as documentation)
  * docs/work-orders/WO-*.md <- each "### WO-<epic>.<n>:" section of Part 7
  * docs/work-orders/README.md <- the non-WO text of Part 7 (index, stretch backlog)
CLAUDE.md comes from its File block in Part 6. Idempotent: identical files are left alone; differing files are only
overwritten with --force. Paths must be relative and stay inside --out.
"""
import argparse
import pathlib
import re
import sys

FENCE = re.compile(r"^(`{3,}|~{3,})(.*)$")
FILE_LINE = re.compile(r"^File: (\S+)\s*$")
PART = re.compile(r"^## Part (\d+) — ")
WO = re.compile(r"^### (WO-[0-9A-Z]+\.\d+): ")
H3 = re.compile(r"^### ")


def scan(lines):
    """Yield (index, line, in_fence) so headings inside code blocks are ignored."""
    fence = None
    for i, line in enumerate(lines):
        m = FENCE.match(line)
        if fence is None and m:
            fence = m.group(1)
            yield i, line, True
            continue
        if fence is not None:
            yield i, line, True
            if line.strip() == fence:
                fence = None
            continue
        yield i, line, False


def extract_files(lines):
    files, i = {}, 0
    while i < len(lines):
        m = FILE_LINE.match(lines[i])
        if m and i + 1 < len(lines) and FENCE.match(lines[i + 1]):
            path, fence = m.group(1), FENCE.match(lines[i + 1]).group(1)
            j = i + 2
            while j < len(lines) and lines[j].strip() != fence:
                j += 1
            if j >= len(lines):
                sys.exit(f"unterminated fence for File: {path} (line {i + 1})")
            if path in files:
                sys.exit(f"duplicate File: {path}")
            files[path] = "\n".join(lines[i + 2:j]) + "\n"
            i = j + 1
            continue
        i += 1
    return files


def split_parts(lines):
    starts = [(i, int(PART.match(l).group(1))) for i, l, f in scan(lines) if not f and PART.match(l)]
    if not starts:
        sys.exit("no '## Part N — ' headings found")
    preamble = lines[:starts[0][0]]
    parts = {}
    for k, (i, n) in enumerate(starts):
        end = starts[k + 1][0] if k + 1 < len(starts) else len(lines)
        parts[n] = lines[i:end]
    return preamble, parts


def split_work_orders(part7):
    wos, other, cur = {}, [], None
    for i, line, in_fence in scan(part7):
        if not in_fence and WO.match(line):
            cur = WO.match(line).group(1)
            if cur in wos:
                sys.exit(f"duplicate work order {cur}")
            wos[cur] = [line]
            continue
        if not in_fence and H3.match(line):
            cur = None
        (wos[cur] if cur else other).append(line)
    for k in wos:
        while wos[k] and wos[k][-1].strip() in ("", "---"):
            wos[k].pop()
    return wos, other


def safe_target(root, rel):
    if rel.startswith(("/", "\\")) or re.match(r"^[A-Za-z]:", rel) or ".." in pathlib.PurePosixPath(rel).parts:
        sys.exit(f"unsafe path in plan: {rel}")
    target = (root / rel).resolve()
    if root.resolve() not in target.parents and target != root.resolve():
        sys.exit(f"path escapes output dir: {rel}")
    return target


def write(root, rel, content, force, dry, stats):
    target = safe_target(root, rel)
    if target.exists():
        if target.read_text(encoding="utf-8") == content:
            stats["same"] += 1
            return
        if not force:
            print(f"differs (use --force): {rel}")
            stats["conflict"] += 1
            return
    stats["written"] += 1
    if dry:
        print(f"would write {rel}")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8", newline="\n")
    if rel.endswith((".sh", ".py")):
        target.chmod(0o755)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("plan")
    ap.add_argument("--out", default=".")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    lines = pathlib.Path(a.plan).read_text(encoding="utf-8").splitlines()
    root = pathlib.Path(a.out)
    stats = {"written": 0, "same": 0, "conflict": 0}

    files = extract_files(lines)
    for rel, content in sorted(files.items()):
        write(root, rel, content, a.force, a.dry_run, stats)

    preamble, parts = split_parts(lines)
    for required in (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13):
        if required not in parts:
            sys.exit(f"missing Part {required}")
    plan = preamble[:]
    for n in (1, 2, 3, 4, 5, 8, 9, 10, 11, 12):
        plan += parts[n]
    write(root, "docs/PLAN.md", "\n".join(plan).rstrip() + "\n", a.force, a.dry_run, stats)

    wos, other = split_work_orders(parts[7])
    for wid, body in sorted(wos.items()):
        write(root, f"docs/work-orders/{wid}.md", "\n".join(body).rstrip() + "\n", a.force, a.dry_run, stats)
    write(root, "docs/work-orders/README.md", "\n".join(other).rstrip() + "\n", a.force, a.dry_run, stats)

    if "CLAUDE.md" not in files:
        sys.exit("CLAUDE.md File block missing (Part 6)")
    print(f"files={len(files)} work_orders={len(wos)} written={stats['written']} unchanged={stats['same']} "
          f"conflicts={stats['conflict']}")
    sys.exit(1 if stats["conflict"] else 0)


if __name__ == "__main__":
    main()

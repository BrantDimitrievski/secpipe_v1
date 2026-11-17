#!/usr/bin/env python3
"""Create one GitHub issue per work order (Part 13, kit item 2). Safe to re-run: an issue whose title starts with
"WO-<id>:" (open or closed) is updated (labels/milestone/body), never duplicated.

Usage: python3 scripts/create-issues.py --repo OWNER/NAME [--dir docs/work-orders] [--branch main] [--dry-run]
Needs: gh authenticated with repo scope. Labels and milestones come from scripts/bootstrap-repo.sh.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys

OWNER_LABEL = {"Abdul Babatunde": "owner:abdul", "Brant Dimitrievski": "owner:brant", "Clinton Odinamadu": "owner:clinton",
               "Elaine Huang": "owner:elaine", "Elodie Abdo": "owner:elodie"}
MILESTONE = {"Prototype 2 Dec 5": ("milestone:p2", "Prototype 2 (Dec 5, 2026)"),
             "Oral Jan 25": ("milestone:oral", "Oral presentations (Jan 25-29, 2027)"),
             "Final Feb 20": ("milestone:final", "Final testing (Feb 20, 2027)"),
             "Stretch": ("milestone:stretch", None)}


def gh(args, dry=False, capture=True):
    if dry and args[0] == "issue" and args[1] in ("create", "edit"):
        print("[dry-run] gh " + " ".join(args[:3]) + " ...")
        return ""
    r = subprocess.run(["gh"] + args, text=True, capture_output=capture)
    if r.returncode != 0:
        sys.exit(f"gh {' '.join(args[:3])} failed: {r.stderr.strip()}")
    return r.stdout


def parse(path):
    t = path.read_text(encoding="utf-8")
    head = re.match(r"^### (WO-([0-9A-Z]+)\.(\d+)): (.+)$", t, re.M)
    meta = re.search(r"^Wave: (\d+)\s+Depends on: (.*?)\s+Blocks: (.*)$", t, re.M)
    ms = re.search(r"^Milestone: (.+)$", t, re.M).group(1).strip()
    owner = re.search(r"^Suggested human owner: (.+?) \(reviewer: (.+?)\)", t, re.M)
    labels = [f"wave:{meta.group(1)}", f"epic:{head.group(2)}", OWNER_LABEL.get(owner.group(1), "")]
    mlabel, mtitle = MILESTONE.get(ms, (None, None))
    if mlabel:
        labels.append(mlabel)
    if head.group(2) == "C":
        labels.append("contract")
    if head.group(2) == "S":
        labels.append("spike")
    if ms == "Stretch":
        labels.append("stretch")
    accept = re.search(r"Acceptance criteria \(all must pass\):\n(.*?)\n\n", t, re.S)
    return {"id": head.group(1), "title": f"{head.group(1)}: {head.group(4).strip()}", "labels": [l for l in labels if l],
            "milestone": mtitle, "deps": meta.group(2).strip(), "blocks": meta.group(3).strip(),
            "owner": owner.group(1), "reviewer": owner.group(2), "accept": accept.group(1) if accept else ""}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--dir", default="docs/work-orders")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    existing = json.loads(gh(["issue", "list", "-R", a.repo, "--state", "all", "--limit", "1000", "--json", "number,title"]) or "[]")
    by_id = {}
    for issue in existing:
        m = re.match(r"^(WO-[0-9A-Z]+\.\d+):", issue["title"])
        if m:
            by_id[m.group(1)] = issue["number"]
    created = updated = 0
    for path in sorted(pathlib.Path(a.dir).glob("WO-*.md")):
        wo = parse(path)
        body = (f"**Work order:** [`{path.as_posix()}`](../blob/{a.branch}/{path.as_posix()})\n\n"
                f"**Depends on:** {wo['deps']}\n**Blocks:** {wo['blocks']}\n"
                f"**Suggested owner:** {wo['owner']} · **Reviewer:** {wo['reviewer']}\n\n"
                f"Branch: `{wo['id'].lower()}-<slug>` · Start a session with `docs/prompts/kickoff.md` (replace `<WO-ID>` with `{wo['id']}`).\n\n"
                f"### Acceptance criteria\n{wo['accept']}\n")
        args = ["-R", a.repo, "--title", wo["title"], "--body", body]
        for lab in wo["labels"]:
            args += ["--add-label" if wo["id"] in by_id else "--label", lab]
        if wo["milestone"]:
            args += ["--milestone", wo["milestone"]]
        if wo["id"] in by_id:
            gh(["issue", "edit", str(by_id[wo["id"]])] + args, a.dry_run)
            updated += 1
        else:
            gh(["issue", "create"] + args, a.dry_run)
            created += 1
    print(f"created={created} updated={updated}")


if __name__ == "__main__":
    main()

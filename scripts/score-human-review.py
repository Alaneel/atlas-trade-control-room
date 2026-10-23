#!/usr/bin/env python3
"""Validate and score local annotation files. Empty/incomplete cases never imply wins."""
import argparse
import json
from pathlib import Path
from human_review import load, score


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packet", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--annotations", type=Path, nargs="*", default=[])
    parser.add_argument("--output", type=Path, required=True, help="New file; never overwritten")
    args = parser.parse_args()
    result = score(load(args.packet), load(args.mapping), [load(p) for p in args.annotations])
    with args.output.open("x") as handle:
        handle.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: result[k] for k in ("human_review", "reviewer_files", "eligible_cases",
        "completed_case_ratings", "unique_cases_reviewed", "ai_vs_strong_rules")}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError):
        raise SystemExit("Review validation failed; no score written. Check packet identity, fields and duplicate reviews.")

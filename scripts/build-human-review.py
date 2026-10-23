#!/usr/bin/env python3
"""Build an offline reviewer folder and a separate organizer mapping; no credentials needed."""
import argparse
import json
from pathlib import Path
from human_review import load, make_packet, template, score
from human_review_ui import render


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="New directory; never overwritten")
    parser.add_argument("--seed", type=int, default=20261023)
    args = parser.parse_args()
    packet, key = make_packet(load(args.results), args.seed)
    annotation = template(packet)
    args.output.mkdir(parents=True, exist_ok=False)
    reviewer = args.output / "reviewer"
    organizer = args.output / "organizer"
    reviewer.mkdir()
    organizer.mkdir(mode=0o700)
    def write(path, value):
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    write(reviewer / "packet.json", packet)
    write(reviewer / "annotations-template.json", annotation)
    (reviewer / "review.html").write_text(render(packet, annotation))
    write(organizer / "source-mapping.json", key)
    (organizer / "source-mapping.json").chmod(0o600)
    write(organizer / "pending-summary.json", score(packet, key, []))
    (args.output / "README.md").write_text(
        "# Local human review\n\nShare only `reviewer/` with a person who has not seen source identities. "
        "Open `review.html` directly in a browser, choose a pseudonym, rate the candidates, and export JSON. "
        "The page is offline and does not save automatically. Import an exported JSON to resume.\n\n"
        "Keep `organizer/` away from reviewers until annotations are frozen. Anonymous presentation is procedural blinding, "
        "not encryption; wording may reveal source. No actual human rating is included. "
        "Use different seeds for different reviewers if varying positions; the current scorer accepts one packet per invocation. "
        "Do not pool multiple seeds or raters as additional independent cases.\n\n"
        "Score with: `python3 scripts/score-human-review.py --packet <reviewer/packet.json> "
        "--mapping <organizer/source-mapping.json> --annotations <export.json> --output <new-summary.json>`\n")
    print(json.dumps({"packet_id": packet["packet_id"], "cases": len(packet["cases"]),
        "human_reviews_completed": 0, "output": str(args.output)}))


if __name__ == "__main__":
    main()

"""Compare fixed-answer continuation probes. Abstention and partial answers are not full matches."""
import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path


def summarize(data):
    groups = defaultdict(list)
    for case in data["cases"]:
        if "expected" in case:
            groups[case.get("policy", "legacy")].append(case)
    output = {}
    for name, cases in groups.items():
        first, all_choices, formats = Counter(), Counter(), defaultdict(Counter)
        failed = 0
        for case in cases:
            grades = case["grades"]
            state = case["result"].get("state")
            failed += state not in ("generated", "abstained", "empty")
            grade = grades[0] if grades else "abstain" if state in ("abstained", "empty") else "failed"
            first[grade] += 1
            all_choices.update(grades)
            form = case.get("format", "comma" if case["prefix"].endswith("，") else "plain")
            formats[form][grade] += 1
        output[name] = {"inputs": len(cases), "top1": dict(first), "all_candidates": dict(all_choices),
                        "failed_inputs": failed, "by_input_format": {k: dict(v) for k, v in formats.items()}}
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = {
        "metric": "Exact next-line characters ignoring punctuation/whitespace. Partial, overrun, wrong and abstain are separate.",
        "limitation": "Small diagnostic set of memorized public-domain poems, used during development; not a held-out accuracy or calibrated correctness claim.",
        "baseline": summarize(json.loads(args.baseline.read_text(encoding="utf-8-sig"))),
        "after": summarize(json.loads(args.after.read_text(encoding="utf-8-sig"))),
    }
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)

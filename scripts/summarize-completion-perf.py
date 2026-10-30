"""Summarize a Feinianyu completion export; never print input or generated text."""
import argparse
import json
from collections import Counter
from pathlib import Path


def percentile(values, p):
    values = sorted(values)
    if not values:
        return None
    index = (len(values) - 1) * p
    low = int(index)
    high = min(low + 1, len(values) - 1)
    return round(values[low] + (values[high] - values[low]) * (index - low), 2)


def summarize(data):
    records = data["records"]
    original_count = len(records)
    commit = data.get("build_commit")
    if commit:
        records = [r for r in records if r.get("build_commit") == commit]
    inference = [r for r in records if r.get("phase") == "inference"]
    generated = [r for r in inference if r.get("state") == "generated"]
    shown = [r for r in records if r.get("phase") == "ui" and r.get("state") == "shown"]
    summary = {
        "device": data.get("device"),
        "app_version": data.get("app_version"),
        "build_commit": commit,
        "excluded_other_build_records": original_count - len(records),
        "model_id": data.get("model_id"),
        "inference_count": len(inference),
        "inference_outcomes": dict(Counter(r.get("state") for r in inference)),
        "ui_outcomes": dict(Counter(r.get("state") for r in records if r.get("phase") == "ui")),
        "shown_count": len(shown),
        "candidate_counts": dict(Counter(str(r.get("candidate_count", 1)) for r in generated)),
        "selected_indices": dict(Counter(str(r.get("selected_index", 0)) for r in records if r.get("phase") == "accept")),
        "ui_budgets_ms": sorted(set(r["budget_ms"] for r in records if r.get("phase") == "ui" and "budget_ms" in r)),
        "abstain_reasons": dict(Counter(r.get("abstain_reason", "unknown") for r in inference if r.get("state") == "abstained")),
        "by_confidence_policy": {},
        "metrics": {},
        "note": "Top-level timing percentiles describe generated/shown samples; by_confidence_policy includes all outcomes. Timeout/cancel counts are separate. Emulator results are not phone performance.",
    }
    for key in ("prefill_ms", "first_token_ms", "decode_ms", "native_ms", "worker_queue_ms"):
        values = [r[key] for r in generated if isinstance(r.get(key), (int, float))]
        summary["metrics"][key] = {"n": len(values), "p50": percentile(values, .5), "p95": percentile(values, .95), "max": round(max(values), 2) if values else None}
    values = [r["end_to_end_ms"] for r in shown]
    summary["metrics"]["end_to_end_ms"] = {"n": len(values), "p50": percentile(values, .5), "p95": percentile(values, .95), "max": max(values) if values else None}
    summary["loads"] = [r for r in records if r.get("phase") == "load"]
    memory = [r["process_peak_rss_kb"] for r in records if isinstance(r.get("process_peak_rss_kb"), (int, float))]
    summary["process_peak_rss_mib"] = round(max(memory) / 1024, 1) if memory else None
    for policy in sorted(set(r.get("confidence_policy", "legacy") for r in inference)):
        group = [r for r in inference if r.get("confidence_policy", "legacy") == policy]
        values = [r["native_ms"] for r in group if isinstance(r.get("native_ms"), (int, float))]
        summary["by_confidence_policy"][policy] = {
            "outcomes": dict(Counter(r.get("state") for r in group)),
            "candidate_counts": dict(Counter(str(r.get("candidate_count", 0)) for r in group)),
            "native_ms_all_outcomes": {"n": len(values), "p50": percentile(values, .5), "p95": percentile(values, .95)},
        }
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("export", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    text = json.dumps(summarize(json.loads(args.export.read_text(encoding="utf-8-sig"))), ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)

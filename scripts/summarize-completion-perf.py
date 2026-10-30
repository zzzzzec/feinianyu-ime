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
        "metrics": {},
        "note": "Timing percentiles below describe generated/shown samples only; all timeout/cancel counts are reported separately. Emulator results are not phone performance.",
    }
    for key in ("prefill_ms", "first_token_ms", "decode_ms", "native_ms", "worker_queue_ms"):
        values = [r[key] for r in generated if isinstance(r.get(key), (int, float))]
        summary["metrics"][key] = {"n": len(values), "p50": percentile(values, .5), "p95": percentile(values, .95), "max": round(max(values), 2) if values else None}
    values = [r["end_to_end_ms"] for r in shown]
    summary["metrics"]["end_to_end_ms"] = {"n": len(values), "p50": percentile(values, .5), "p95": percentile(values, .95), "max": max(values) if values else None}
    summary["loads"] = [r for r in records if r.get("phase") == "load"]
    memory = [r["process_peak_rss_kb"] for r in records if isinstance(r.get("process_peak_rss_kb"), (int, float))]
    summary["process_peak_rss_mib"] = round(max(memory) / 1024, 1) if memory else None
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

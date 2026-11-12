#!/usr/bin/env python3
"""Compute only data-source availability metrics for the ramen20 PoC."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", type=Path, default=ROOT / "api_results.csv")
    parser.add_argument("--output", type=Path, default=ROOT / "metrics.json")
    return parser.parse_args()


def as_int(value: str) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def main() -> int:
    args = parse_args()
    with args.results.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    if len(rows) != 20:
        raise SystemExit(f"Expected 20 result rows, found {len(rows)}")

    c005_evaluable = [
        row for row in rows if row.get("c005_result_code", "") in {"INFO-000", "INFO-200"}
    ]
    exact_rows = [row for row in rows if as_int(row.get("c005_exact_match_count", "0")) > 0]
    raw_present = [row for row in exact_rows if as_int(row.get("c002_raw_material_count", "0")) > 0]
    recent_rows = [row for row in rows if row.get("is_recent", "").lower() == "true"]
    recent_evaluable = [
        row for row in recent_rows if row.get("c005_result_code", "") in {"INFO-000", "INFO-200"}
    ]
    recent_exact = [row for row in recent_rows if as_int(row.get("c005_exact_match_count", "0")) > 0]
    status_counts: dict[str, int] = {}
    for row in rows:
        status = row.get("status", "") or "UNKNOWN"
        status_counts[status] = status_counts.get(status, 0) + 1

    barcode_metric_status = (
        "EVALUATED"
        if len(c005_evaluable) == len(rows)
        else "NOT_EVALUATED"
        if not c005_evaluable
        else "PARTIAL"
    )
    recent_metric_status = (
        "EVALUATED"
        if len(recent_evaluable) == len(recent_rows)
        else "NOT_EVALUATED"
        if not recent_evaluable
        else "PARTIAL"
    )
    downstream_evaluable = [
        row for row in exact_rows if row.get("status") in {"EXACT_MATCH", "RAW_MATERIAL_MISSING"}
    ]
    raw_metric_status = (
        "NOT_EVALUATED"
        if barcode_metric_status == "NOT_EVALUATED"
        else "EVALUATED"
        if len(downstream_evaluable) == len(exact_rows)
        else "PARTIAL"
    )

    metrics: dict[str, Any] = {
        "manifest_count": len(rows),
        "barcode_exact_match": {
            "status": barcode_metric_status,
            "numerator": len(exact_rows),
            "denominator": len(rows),
            "evaluable_count": len(c005_evaluable),
        },
        "raw_material_presence_among_exact_matches": {
            "status": raw_metric_status,
            "numerator": len(raw_present),
            "denominator": len(exact_rows),
            "evaluable_exact_match_count": len(downstream_evaluable),
        },
        "recent_product_barcode_match": {
            "status": recent_metric_status,
            "numerator": len(recent_exact),
            "denominator": len(recent_rows),
            "evaluable_count": len(recent_evaluable),
        },
        "status_counts": status_counts,
        "allergen_recall": "NOT_EVALUATED",
        "false_negative": "NOT_EVALUATED",
        "api_missing_allergens": "NOT_EVALUATED",
        "final_safety": "NOT_EVALUATED",
    }
    args.output.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")

    if barcode_metric_status == "EVALUATED":
        print(f"Barcode exact match: {len(exact_rows)} / {len(rows)}")
    else:
        print(
            "Barcode exact match: "
            f"{barcode_metric_status} ({len(c005_evaluable)} / {len(rows)} calls evaluable)"
        )
    if raw_metric_status == "EVALUATED":
        print(f"Raw-material presence among exact matches: {len(raw_present)} / {len(exact_rows)}")
    else:
        print(
            "Raw-material presence among exact matches: "
            f"{raw_metric_status} ({len(downstream_evaluable)} evaluable exact matches)"
        )
    if recent_metric_status == "EVALUATED":
        print(f"Recent product barcode match: {len(recent_exact)} / {len(recent_rows)}")
    else:
        print(
            "Recent product barcode match: "
            f"{recent_metric_status} ({len(recent_evaluable)} / {len(recent_rows)} calls evaluable)"
        )
    print("Allergen Recall / False Negative / final safety: NOT_EVALUATED")
    print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

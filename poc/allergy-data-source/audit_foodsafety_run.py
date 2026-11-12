#!/usr/bin/env python3
"""Offline evidence audit; no network, ingredient parsing, or allergen inference.

The original collector CSV is preserved. This audit checks complete successful
responses and keeps each report number's evidence separate. It intentionally
fails on incomplete/error responses instead of publishing misleading rates.
"""

import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def verified_rows(envelope, service):
    require(envelope.get("http_status") == 200, f"{service}: HTTP failure")
    require(not envelope.get("error"), f"{service}: transport failure")
    response = envelope.get("response") or {}
    payload = response.get(service)
    require(isinstance(payload, dict), f"{service}: missing service payload")
    code = (payload.get("RESULT") or {}).get("CODE")
    require(code in {"INFO-000", "INFO-200"}, f"{service}: non-success code {code}")
    rows = payload.get("row", [])
    require(isinstance(rows, list), f"{service}: malformed rows")
    require(all(isinstance(row, dict) for row in rows), f"{service}: malformed record")
    require(str(payload.get("total_count")) == str(len(rows)), f"{service}: incomplete page")
    require((code == "INFO-200") == (len(rows) == 0), f"{service}: code/count conflict")
    return code, rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--collected-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("ramen20_manifest.csv"))
    args = parser.parse_args()
    source = args.collected_dir
    with args.manifest.open(encoding="utf-8-sig", newline="") as handle:
        manifest = list(csv.DictReader(handle))
    with (source / "api_results.csv").open(encoding="utf-8-sig", newline="") as handle:
        collected = list(csv.DictReader(handle))
    require(len(manifest) == len(collected) == 20, "Expected 20 SKUs")
    require(len({m["sku_id"] for m in manifest}) == 20, "Duplicate SKU")
    require(sum(m["is_recent"] == "true" for m in manifest) == 4, "Expected four recent SKUs")

    audited, all_reports, times, codes, calls = [], [], [], Counter(), Counter()

    def consume(envelope, service):
        code, rows = verified_rows(envelope, service)
        times.append(envelope["fetched_at"])
        calls[service] += 1
        codes[code] += 1
        return code, rows

    for item, summary in zip(manifest, collected):
        sku, barcode = item["sku_id"], item["barcode"]
        require(summary["sku_id"] == sku and summary["barcode"] == barcode, "Manifest/CSV mismatch")
        c005 = read_json(source / "raw" / "C005" / f"{sku}.json")
        require(c005["request"]["filters"] == {"BAR_CD": barcode}, f"{sku}: incorrect barcode request")
        c005_code, rows = consume(c005, "C005")
        require(all(r.get("BAR_CD") == barcode for r in rows), f"{sku}: non-exact barcode response")
        require(all(r.get("PRDLST_REPORT_NO") for r in rows), f"{sku}: missing report number")
        numbers = sorted({r["PRDLST_REPORT_NO"] for r in rows})
        require(int(summary["c005_exact_match_count"]) == len(rows), f"{sku}: exact count mismatch")
        require(set(filter(None, summary["c005_report_numbers"].split(" | "))) == set(numbers), f"{sku}: report set mismatch")
        record = {k: item[k] for k in ("sku_id", "product_name", "package", "barcode", "is_recent")}
        record.update(c005_code=c005_code, report_numbers=numbers, c005_records=rows,
                      collector_status=summary["status"], selected_report_number="",
                      identity_status="UNMATCHED", status=summary["status"], reports=[], name_candidate_count=0)

        for service in ("I1250", "C002") if numbers else ():
            envelopes = read_json(source / "raw" / service / f"{sku}.json")["requests"]
            requested = [e["request"]["filters"].get("PRDLST_REPORT_NO") for e in envelopes]
            require(sorted(requested) == numbers, f"{sku}/{service}: request set mismatch")
            for envelope in envelopes:
                number = envelope["request"]["filters"]["PRDLST_REPORT_NO"]
                code, downstream = consume(envelope, service)
                require(all(r.get("PRDLST_REPORT_NO") == number for r in downstream), f"{sku}/{service}: report mismatch")
                entry = next((r for r in record["reports"] if r["report_number"] == number), None)
                if entry is None:
                    entry = {"sku_id": sku, "report_number": number}
                    record["reports"].append(entry)
                entry[service.lower() + "_code"] = code
                entry[service.lower() + "_records"] = downstream

        if not numbers:
            candidates = read_json(source / "raw" / "I1250" / f"{sku}_name_candidates.json")
            require(candidates["request"]["filters"] == {"PRDLST_NM": item["i1250_search_name"]}, f"{sku}: candidate request mismatch")
            _, candidate_rows = consume(candidates, "I1250")
            record["name_candidate_count"] = len(candidate_rows)
            require(record["name_candidate_count"] == int(summary["i1250_name_candidate_count"]), f"{sku}: candidate count mismatch")
            require(summary["c002_raw_material_count"] == "0", f"{sku}: name-based C002 linkage")

        for entry in record["reports"]:
            entry["raw_material_present"] = any(str(r.get("RAWMTRL_NM") or "").strip() for r in entry["c002_records"])
        raw_records = sum(len(r["c002_records"]) for r in record["reports"])
        require(raw_records == int(summary["c002_raw_material_count"]), f"{sku}: C002 record count mismatch")
        record["reports_with_raw_materials"] = sum(r["raw_material_present"] for r in record["reports"])
        record["all_reports_have_raw_materials"] = bool(numbers) and record["reports_with_raw_materials"] == len(numbers)
        if len(numbers) > 1:
            record.update(identity_status="UNKNOWN_MULTIPLE_REPORTS", status="UNKNOWN")
        elif len(numbers) == 1:
            record.update(identity_status="UNIQUE_REPORT_CANDIDATE", selected_report_number=numbers[0])
        require(summary["api_observable_allergens"] == "", f"{sku}: automatic allergen inference")
        for field in ("allergen_recall", "false_negative", "api_missing_allergens", "final_safety"):
            require(summary[field] == "NOT_EVALUATED", f"{sku}: premature safety evaluation")
        audited.append(record)
        all_reports.extend(record["reports"])

    matched = [r for r in audited if r["report_numbers"]]
    single = [r for r in matched if len(r["report_numbers"]) == 1]
    recent = [r for r in audited if r["is_recent"] == "true"]
    def metric(n, d, definition):
        return {"status": "EVALUATED" if d else "NOT_APPLICABLE", "numerator": n,
                "denominator": d, "rate": n / d if d else None, "definition": definition}
    metrics = {
        "barcode_exact_match": metric(len(matched), 20, "Exact barcode has at least one nonempty report number; not unique product identification."),
        "raw_material_presence_among_exact_matches": metric(sum(r["reports_with_raw_materials"] > 0 for r in matched), len(matched), "At least one exact-linked report has nonempty RAWMTRL_NM; not all reports or ingredients."),
        "recent_product_barcode_match": metric(sum(bool(r["report_numbers"]) for r in recent), 4, "Exact barcode report presence in the four fixed recent SKUs."),
        "unique_report_candidate": metric(len(single), 20, "One report number in C005; physical label identity remains unverified."),
        "raw_material_presence_among_unique_report_candidates": metric(sum(r["reports_with_raw_materials"] > 0 for r in single), len(single), "C002 presence restricted to the unique-report subset."),
        "all_linked_reports_have_raw_materials": metric(sum(r["all_reports_have_raw_materials"] for r in matched), len(matched), "Every exact-linked report of the SKU has nonempty RAWMTRL_NM."),
        "raw_material_presence_per_report_link": metric(sum(r["raw_material_present"] for r in all_reports), len(all_reports), "Per SKU/report-number pair, not individual ingredient count."),
        "ambiguous_sku_count": len(matched) - len(single),
        "identity_status_counts": dict(Counter(r["identity_status"] for r in audited)),
        "audited_status_counts": dict(Counter(r["status"] for r in audited)),
        "request_counts": dict(calls), "response_codes": dict(codes),
        "first_response_at": min(times), "last_response_at": max(times),
        "manifest_sha256": hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
        "checks": "PASS: complete pages, exact request/response keys, CSV counts, and untouched safety fields",
        "allergen_recall": "NOT_EVALUATED", "false_negative": "NOT_EVALUATED",
        "api_missing_allergens": "NOT_EVALUATED", "final_safety": "NOT_EVALUATED",
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, value in (("metrics.json", metrics), ("identity_audit.json", audited)):
        with (args.output_dir / name).open("x", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2)
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

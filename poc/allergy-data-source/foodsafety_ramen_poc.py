#!/usr/bin/env python3
"""Independent FoodSafetyKorea data-source PoC for the fixed ramen20 manifest.

This script deliberately does not perform allergen matching, label parsing, OCR,
or product-name auto-matching. The API key is read only from the process
environment and is never written to an artifact or printed.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


BASE_URL = "https://openapi.foodsafetykorea.go.kr"
SERVICE_IDS = ("C005", "I1250", "C002")
SUCCESS_CODES = {"", "INFO-000", "INFO-200"}
ROOT = Path(__file__).resolve().parent


RESULT_FIELDS = [
    "sku_id",
    "manufacturer",
    "product_name",
    "package",
    "barcode",
    "is_recent",
    "barcode_source_url",
    "c005_result_code",
    "c005_result_message",
    "c005_returned_count",
    "c005_exact_match_count",
    "c005_product_names",
    "c005_report_numbers",
    "c005_report_dates",
    "c005_production_end_dates",
    "c005_business_close_dates",
    "c005_manufacturers",
    "c005_food_types",
    "c005_addresses",
    "i1250_result_codes",
    "i1250_basic_record_count",
    "i1250_product_names",
    "i1250_food_types",
    "i1250_manufacturers",
    "i1250_report_dates",
    "i1250_production_flags",
    "i1250_last_update_dates",
    "i1250_name_candidate_count",
    "i1250_name_candidate_names",
    "i1250_name_candidate_report_numbers",
    "c002_result_codes",
    "c002_raw_material_count",
    "c002_raw_material_names",
    "c002_raw_material_orders",
    "c002_change_dates",
    "api_observable_allergens",
    "allergen_recall",
    "false_negative",
    "api_missing_allergens",
    "final_safety",
    "status",
    "error",
]


class ApiFailure(RuntimeError):
    """Raised for transport, decoding, or service-contract failures."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT / "ramen20_manifest.csv")
    parser.add_argument("--output-dir", type=Path, default=ROOT)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--request-delay", type=float, default=0.05)
    return parser.parse_args()


def redact(text: str, api_key: str) -> str:
    return text.replace(api_key, "[REDACTED]") if api_key else text


def safe_error(exc: BaseException, api_key: str) -> str:
    message = redact(str(exc), api_key)
    message = re.sub(r"https?://\S+", "[URL_REDACTED]", message)
    return message[:1000]


def request_service(
    api_key: str,
    service_id: str,
    filters: dict[str, str],
    timeout: float,
    end_index: int = 1000,
) -> tuple[int, dict[str, Any]]:
    if service_id not in SERVICE_IDS:
        raise ValueError(f"Unsupported service: {service_id}")
    filter_segment = "&".join(
        f"{name}={quote(str(value), safe='')}" for name, value in filters.items()
    )
    path = f"/api/{quote(api_key, safe='')}/{service_id}/json/1/{end_index}"
    if filter_segment:
        path = f"{path}/{filter_segment}"
    request = Request(
        f"{BASE_URL}{path}",
        headers={"Accept": "application/json", "User-Agent": "Salus-FoodSafety-PoC/1.0"},
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            status = int(response.status)
            body = response.read().decode("utf-8-sig")
    except (HTTPError, URLError, TimeoutError, OSError) as exc:
        raise ApiFailure(safe_error(exc, api_key)) from exc
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError as exc:
        raise ApiFailure(f"Invalid JSON response for {service_id}: {exc.msg}") from exc
    if not isinstance(parsed, dict):
        raise ApiFailure(f"Unexpected JSON root for {service_id}")
    serialized = json.dumps(parsed, ensure_ascii=False)
    if api_key in serialized:
        parsed = json.loads(serialized.replace(api_key, "[REDACTED]"))
    return status, parsed


def service_parts(data: dict[str, Any], service_id: str) -> tuple[str, str, list[dict[str, Any]]]:
    payload = data.get(service_id, data)
    if not isinstance(payload, dict):
        return "", "Malformed service payload", []
    result = payload.get("RESULT") or data.get("RESULT") or {}
    if not isinstance(result, dict):
        result = {}
    code = str(result.get("CODE", "")).strip()
    message = str(result.get("MSG", "")).strip()
    rows = payload.get("row") or []
    if isinstance(rows, dict):
        rows = [rows]
    rows = [row for row in rows if isinstance(row, dict)]
    return code, message, rows


def raw_envelope(
    service_id: str,
    request_kind: str,
    filters: dict[str, str],
    http_status: int | None,
    response: dict[str, Any] | None,
    error: str = "",
) -> dict[str, Any]:
    return {
        "service_id": service_id,
        "request_kind": request_kind,
        "request": {"filters": filters, "start_index": 1, "end_index": 1000},
        "fetched_at": datetime.now(timezone.utc).isoformat(),
        "http_status": http_status,
        "error": error,
        "response": response,
    }


def save_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def unique(values: Iterable[Any]) -> list[str]:
    seen: set[str] = set()
    output: list[str] = []
    for value in values:
        text = str(value or "").strip()
        if text and text not in seen:
            seen.add(text)
            output.append(text)
    return output


def joined(values: Iterable[Any]) -> str:
    return " | ".join(unique(values))


def call_and_capture(
    api_key: str,
    service_id: str,
    request_kind: str,
    filters: dict[str, str],
    timeout: float,
) -> tuple[dict[str, Any], str, str, list[dict[str, Any]], dict[str, Any]]:
    try:
        http_status, response = request_service(api_key, service_id, filters, timeout)
        code, message, rows = service_parts(response, service_id)
        envelope = raw_envelope(
            service_id, request_kind, filters, http_status, response
        )
        return envelope, code, message, rows, response
    except ApiFailure as exc:
        error = safe_error(exc, api_key)
        envelope = raw_envelope(service_id, request_kind, filters, None, None, error)
        return envelope, "TRANSPORT_ERROR", error, [], {}


def initial_result(manifest_row: dict[str, str]) -> dict[str, Any]:
    result = {field: "" for field in RESULT_FIELDS}
    for field in (
        "sku_id",
        "manufacturer",
        "product_name",
        "package",
        "barcode",
        "is_recent",
        "barcode_source_url",
    ):
        result[field] = manifest_row.get(field, "")
    result.update(
        {
            "c005_returned_count": 0,
            "c005_exact_match_count": 0,
            "i1250_basic_record_count": 0,
            "i1250_name_candidate_count": 0,
            "c002_raw_material_count": 0,
            "api_observable_allergens": "",
            "allergen_recall": "NOT_EVALUATED",
            "false_negative": "NOT_EVALUATED",
            "api_missing_allergens": "NOT_EVALUATED",
            "final_safety": "NOT_EVALUATED",
        }
    )
    return result


def run_one(
    manifest_row: dict[str, str],
    api_key: str,
    raw_root: Path,
    timeout: float,
    request_delay: float,
) -> dict[str, Any]:
    result = initial_result(manifest_row)
    sku_id = manifest_row["sku_id"]
    barcode = manifest_row["barcode"].strip()

    c005_envelope, c005_code, c005_message, c005_rows, _ = call_and_capture(
        api_key, "C005", "exact_barcode", {"BAR_CD": barcode}, timeout
    )
    save_json(raw_root / "C005" / f"{sku_id}.json", c005_envelope)
    result["c005_result_code"] = c005_code
    result["c005_result_message"] = c005_message
    result["c005_returned_count"] = len(c005_rows)

    exact_rows = [
        row for row in c005_rows if str(row.get("BAR_CD", "")).strip() == barcode
    ]
    report_numbers = unique(row.get("PRDLST_REPORT_NO") for row in exact_rows)
    result["c005_exact_match_count"] = len(exact_rows)
    result["c005_product_names"] = joined(row.get("PRDLST_NM") for row in exact_rows)
    result["c005_report_numbers"] = " | ".join(report_numbers)
    result["c005_report_dates"] = joined(row.get("PRMS_DT") for row in exact_rows)
    result["c005_production_end_dates"] = joined(row.get("END_DT") for row in exact_rows)
    result["c005_business_close_dates"] = joined(row.get("CLSBIZ_DT") for row in exact_rows)
    result["c005_manufacturers"] = joined(row.get("BSSH_NM") for row in exact_rows)
    result["c005_food_types"] = joined(row.get("PRDLST_DCNM") for row in exact_rows)
    result["c005_addresses"] = joined(row.get("SITE_ADDR") for row in exact_rows)

    if c005_code not in SUCCESS_CODES:
        result["status"] = "API_ERROR"
        result["error"] = f"C005 {c005_code}: {c005_message}".strip()
        return result

    if not exact_rows:
        search_name = manifest_row.get("i1250_search_name", "").strip()
        time.sleep(request_delay)
        envelope, code, message, candidate_rows, _ = call_and_capture(
            api_key, "I1250", "name_candidate_discovery", {"PRDLST_NM": search_name}, timeout
        )
        save_json(raw_root / "I1250" / f"{sku_id}_name_candidates.json", envelope)
        result["i1250_result_codes"] = code
        result["i1250_name_candidate_count"] = len(candidate_rows)
        result["i1250_name_candidate_names"] = joined(
            row.get("PRDLST_NM") for row in candidate_rows
        )
        result["i1250_name_candidate_report_numbers"] = joined(
            row.get("PRDLST_REPORT_NO") for row in candidate_rows
        )
        if code not in SUCCESS_CODES:
            result["status"] = "API_ERROR"
            result["error"] = f"I1250 name discovery {code}: {message}".strip()
        elif candidate_rows:
            result["status"] = "NAME_CANDIDATES_ONLY"
        else:
            result["status"] = "BARCODE_MISS"
        return result

    if not report_numbers:
        result["status"] = "API_ERROR"
        result["error"] = "C005 exact barcode row has no PRDLST_REPORT_NO"
        return result

    i1250_envelopes: list[dict[str, Any]] = []
    i1250_rows: list[dict[str, Any]] = []
    i1250_codes: list[str] = []
    c002_envelopes: list[dict[str, Any]] = []
    c002_rows: list[dict[str, Any]] = []
    c002_codes: list[str] = []
    downstream_errors: list[str] = []

    for report_number in report_numbers:
        time.sleep(request_delay)
        envelope, code, message, rows, _ = call_and_capture(
            api_key,
            "I1250",
            "report_number_lookup",
            {"PRDLST_REPORT_NO": report_number},
            timeout,
        )
        i1250_envelopes.append(envelope)
        i1250_codes.append(code)
        i1250_rows.extend(rows)
        if code not in SUCCESS_CODES:
            downstream_errors.append(f"I1250 {report_number} {code}: {message}")

        time.sleep(request_delay)
        envelope, code, message, rows, _ = call_and_capture(
            api_key,
            "C002",
            "report_number_lookup",
            {"PRDLST_REPORT_NO": report_number},
            timeout,
        )
        c002_envelopes.append(envelope)
        c002_codes.append(code)
        c002_rows.extend(rows)
        if code not in SUCCESS_CODES:
            downstream_errors.append(f"C002 {report_number} {code}: {message}")

    save_json(
        raw_root / "I1250" / f"{sku_id}.json",
        {"service_id": "I1250", "requests": i1250_envelopes},
    )
    save_json(
        raw_root / "C002" / f"{sku_id}.json",
        {"service_id": "C002", "requests": c002_envelopes},
    )

    result["i1250_result_codes"] = " | ".join(unique(i1250_codes))
    result["i1250_basic_record_count"] = len(i1250_rows)
    result["i1250_product_names"] = joined(row.get("PRDLST_NM") for row in i1250_rows)
    result["i1250_food_types"] = joined(row.get("PRDLST_DCNM") for row in i1250_rows)
    result["i1250_manufacturers"] = joined(row.get("BSSH_NM") for row in i1250_rows)
    result["i1250_report_dates"] = joined(row.get("PRMS_DT") for row in i1250_rows)
    result["i1250_production_flags"] = joined(row.get("PRODUCTION") for row in i1250_rows)
    result["i1250_last_update_dates"] = joined(row.get("LAST_UPDT_DTM") for row in i1250_rows)

    result["c002_result_codes"] = " | ".join(unique(c002_codes))
    result["c002_raw_material_count"] = len(c002_rows)
    result["c002_raw_material_names"] = joined(row.get("RAWMTRL_NM") for row in c002_rows)
    result["c002_raw_material_orders"] = joined(row.get("RAWMTRL_ORDNO") for row in c002_rows)
    result["c002_change_dates"] = joined(row.get("CHNG_DT") for row in c002_rows)

    if downstream_errors:
        result["status"] = "API_ERROR"
        result["error"] = " | ".join(downstream_errors)
    elif c002_rows:
        result["status"] = "EXACT_MATCH"
    else:
        result["status"] = "RAW_MATERIAL_MISSING"
    return result


def load_manifest(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    required = {"sku_id", "manufacturer", "product_name", "barcode", "i1250_search_name", "is_recent"}
    missing = required.difference(rows[0].keys() if rows else set())
    if missing:
        raise ValueError(f"Manifest missing columns: {', '.join(sorted(missing))}")
    if len(rows) != 20:
        raise ValueError(f"Manifest must contain exactly 20 rows, found {len(rows)}")
    if len({row["sku_id"] for row in rows}) != 20:
        raise ValueError("Manifest sku_id values must be unique")
    if len({row["barcode"] for row in rows}) != 20:
        raise ValueError("Manifest barcode values must be unique")
    if sum(row["is_recent"].lower() == "true" for row in rows) != 4:
        raise ValueError("Manifest must contain exactly four recent products")
    return rows


def main() -> int:
    args = parse_args()
    api_key = os.environ.get("FOODSAFETY_API_KEY", "").strip()
    if not api_key:
        print("FOODSAFETY_API_KEY is NOT SET.", file=sys.stderr)
        print('Run: export FOODSAFETY_API_KEY="..."', file=sys.stderr)
        return 2

    try:
        manifest = load_manifest(args.manifest)
    except (OSError, ValueError) as exc:
        print(f"Manifest error: {exc}", file=sys.stderr)
        return 2

    output_dir = args.output_dir.resolve()
    raw_root = output_dir / "raw"
    for service_id in SERVICE_IDS:
        (raw_root / service_id).mkdir(parents=True, exist_ok=True)

    results: list[dict[str, Any]] = []
    for index, manifest_row in enumerate(manifest, start=1):
        result = run_one(
            manifest_row,
            api_key,
            raw_root,
            args.timeout,
            args.request_delay,
        )
        results.append(result)
        print(f"[{index:02d}/20] {manifest_row['sku_id']}: {result['status']}")

    output_path = output_dir / "api_results.csv"
    with output_path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=RESULT_FIELDS)
        writer.writeheader()
        writer.writerows(results)
    print(f"Wrote {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

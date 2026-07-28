#!/usr/bin/env python3
"""YÖK Atlas resmî JSON API'sinden 2022-2025 lisans verisi üretir.

Taban puan, başarı sırası, kontenjan ve yerleşen sayıları alınır.
2026 verisi özellikle dışarıda bırakılır.
"""
from __future__ import annotations

import json
import math
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests

ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app" / "src" / "main" / "assets"
PUBLIC_DIR = ROOT / "public" / "data"
YEARS = [2022, 2023, 2024, 2025]
API_URL = "https://yokatlas.yok.gov.tr/api/tercih-kilavuz/search"


def safe_float(value: Any) -> float | None:
    if value in (None, ""):
        return None
    try:
        num = float(str(value).replace(",", "."))
    except (TypeError, ValueError):
        return None
    if math.isnan(num) or math.isinf(num):
        return None
    return round(num, 5)


def safe_int(value: Any) -> int | None:
    if value in (None, ""):
        return None
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def post_page(session: requests.Session, page: int, size: int) -> dict[str, Any]:
    body = {
        "filters": {"birimTuruId": 46},
        "page": page,
        "size": size,
        "sortBy": "basariSirasi",
        "direction": "ASC",
    }
    last_error: Exception | None = None
    for attempt in range(6):
        try:
            response = session.post(API_URL, json=body, timeout=120)
            if response.status_code in (418, 429, 500, 502, 503, 504):
                raise RuntimeError(f"HTTP {response.status_code}: {response.text[:300]}")
            response.raise_for_status()
            data = response.json()
            if not isinstance(data, dict) or "content" not in data:
                raise RuntimeError(f"Beklenmeyen API yanıtı: {str(data)[:500]}")
            return data
        except Exception as exc:
            last_error = exc
            if attempt == 5:
                break
            wait = 1.5 * (attempt + 1)
            print(f"Sayfa {page} deneme {attempt + 1} başarısız: {exc}; {wait:.1f} sn bekleniyor")
            time.sleep(wait)
    raise RuntimeError(f"YÖK Atlas sayfası alınamadı: page={page}; hata={last_error}")


def fetch_all_rows() -> list[dict[str, Any]]:
    size = 100
    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": "Mozilla/5.0 (Android; TercihRadari/1.1)",
            "Accept": "application/json, text/plain, */*",
            "Content-Type": "application/json",
            "Origin": "https://yokatlas.yok.gov.tr",
            "Referer": "https://yokatlas.yok.gov.tr/",
        }
    )
    first = post_page(session, 0, size)
    total_pages = safe_int(first.get("totalPages")) or 1
    total_elements = safe_int(first.get("totalElements")) or len(first.get("content", []))
    rows = list(first.get("content", []))
    print(
        f"YÖK Atlas: toplam={total_elements}, sayfa={total_pages}, "
        f"ilk_sayfa={len(rows)}, size={size}, api_yili={first.get('yil')}"
    )
    for page_no in range(1, total_pages):
        page = post_page(session, page_no, size)
        content = page.get("content", [])
        rows.extend(content)
        if page_no % 10 == 0 or page_no + 1 == total_pages:
            print(f"YÖK Atlas sayfa {page_no + 1}/{total_pages}: {len(content)}")
        time.sleep(0.12)
    print(f"Toplam ham satır: {len(rows)}")
    return rows


def yearly_from_flat(row: dict[str, Any], offset: int) -> dict[str, Any] | None:
    current_year = safe_int(row.get("yil"))
    if current_year is None:
        return None
    year = current_year - offset
    if year not in YEARS:
        return None
    suffix = "" if offset == 0 else str(offset)
    # API güncel yılda genel kontenjanı `kontenjan`, önceki yıllarda `gk1/gk2/gk3` gönderiyor.
    quota_value = row.get("kontenjan") if offset == 0 else row.get(f"gk{offset}")
    placed_value = row.get("gkY") if offset == 0 else row.get(f"gkY{offset}")
    return {
        "year": year,
        "code": str(row.get("kilavuzKodu") or ""),
        "university": row.get("universiteAdi") or "",
        "program": row.get("birimAdi") or row.get("birimGrupAdi") or "",
        "score_type": row.get("puanTuru") or "",
        "quota": safe_int(quota_value),
        "placed": safe_int(placed_value),
        "min_score": safe_float(row.get(f"minPuan{suffix}")),
        "rank": safe_int(row.get(f"basariSirasi{suffix}")),
        "source_url": "https://yokatlas.yok.gov.tr/",
        "source_kind": "YÖK Atlas resmî tercih kılavuzu JSON API",
    }


def build_dataset(rows: list[dict[str, Any]]) -> dict[str, Any]:
    programs: list[dict[str, Any]] = []
    seen: set[str] = set()
    for row in rows:
        code = str(row.get("kilavuzKodu") or "").strip()
        if not code or code in seen:
            continue
        seen.add(code)
        history = [item for offset in range(4) if (item := yearly_from_flat(row, offset)) is not None]
        history.sort(key=lambda item: item["year"])
        if not history:
            continue
        latest = history[-1]
        ranks = [item["rank"] for item in history if item["rank"] is not None]
        scores = [item["min_score"] for item in history if item["min_score"] is not None]

        rank_trend = None
        rank_volatility = None
        if len(ranks) >= 2:
            rank_trend = ranks[-1] - ranks[0]
            diffs = [ranks[i] - ranks[i - 1] for i in range(1, len(ranks))]
            avg = sum(diffs) / len(diffs)
            rank_volatility = round(math.sqrt(sum((x - avg) ** 2 for x in diffs) / len(diffs)), 2)

        score_trend = round(scores[-1] - scores[0], 4) if len(scores) >= 2 else None
        programs.append(
            {
                "code": code,
                "university": row.get("universiteAdi") or "",
                "program": row.get("birimAdi") or row.get("birimGrupAdi") or "",
                "programGroup": row.get("birimGrupAdi") or "",
                "scoreType": row.get("puanTuru") or "",
                "universityType": row.get("universiteTuru") or "",
                "city": row.get("ilAdi") or row.get("fymkIlAdi") or row.get("uniIlAdi") or "",
                "district": row.get("ilceAdi") or row.get("fymkIlceAdi") or "",
                "language": row.get("ogrenimDiliAdi") or "",
                "scholarship": row.get("bursOraniAdi") or "",
                "latestYear": latest["year"],
                "latestMinScore": latest["min_score"],
                "latestRank": latest["rank"],
                "latestQuota": latest["quota"],
                "latestPlaced": latest["placed"],
                "scoreTrend": score_trend,
                "rankTrend": rank_trend,
                "rankVolatility": rank_volatility,
                "history": history,
            }
        )

    programs.sort(
        key=lambda item: (
            item["latestRank"] is None,
            item["latestRank"] if item["latestRank"] is not None else 10**12,
            item["university"],
            item["program"],
        )
    )
    ranked = sum(item["latestRank"] is not None for item in programs)
    quota_count = sum(item["latestQuota"] is not None for item in programs)
    actual_years = sorted({h["year"] for p in programs for h in p["history"]})
    return {
        "schemaVersion": 3,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "years": actual_years,
        "source": "YÖK Atlas resmî tercih kılavuzu JSON API",
        "sourceUrl": "https://yokatlas.yok.gov.tr/",
        "disclaimer": "2026 verisi içermez. Tahminler resmî sonuç değildir.",
        "programCount": len(programs),
        "rankedProgramCount": ranked,
        "quotaProgramCount": quota_count,
        "programs": programs,
    }


def validate(data: dict[str, Any]) -> None:
    assert 2026 not in data["years"]
    assert 2025 in data["years"]
    assert data["programCount"] >= 5000, data["programCount"]
    assert data["rankedProgramCount"] >= 3000, data["rankedProgramCount"]
    assert data["quotaProgramCount"] >= 3000, data["quotaProgramCount"]
    sample = next(item for item in data["programs"] if item["latestRank"] is not None)
    assert "latestQuota" in sample and "latestPlaced" in sample
    print(
        f"Doğrulandı: yıllar={data['years']}, {data['programCount']} program, "
        f"{data['rankedProgramCount']} başarı sıralı, "
        f"{data['quotaProgramCount']} kontenjanlı program"
    )


def main() -> int:
    rows = fetch_all_rows()
    data = build_dataset(rows)
    validate(data)
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    PUBLIC_DIR.mkdir(parents=True, exist_ok=True)
    (ASSET_DIR / "programs.json").write_text(
        json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )
    (PUBLIC_DIR / "programs.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

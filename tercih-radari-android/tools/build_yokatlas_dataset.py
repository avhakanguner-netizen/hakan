#!/usr/bin/env python3
"""YÖK Atlas'ın resmî JSON API'sinden 2022-2025 lisans verisi üretir.

Üretilen veri: taban puan, başarı sırası, kontenjan ve yerleşen sayısı.
2026 verisi özellikle dışarıda bırakılır.
"""
from __future__ import annotations

import json
import math
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from yokatlas_py import SearchFilters, Settings, YokAtlasClient

ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app" / "src" / "main" / "assets"
PUBLIC_DIR = ROOT / "public" / "data"
YEARS = [2022, 2023, 2024, 2025]


def safe_float(value: Any) -> float | None:
    if value is None:
        return None
    try:
        num = float(value)
    except (TypeError, ValueError):
        return None
    if math.isnan(num) or math.isinf(num):
        return None
    return round(num, 5)


def safe_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def fetch_all_programs() -> list[Any]:
    settings = Settings(timeout=120.0)
    filters = SearchFilters(birim_turu_id=46)  # lisans
    programs: list[Any] = []
    page_size = 100
    with YokAtlasClient(settings=settings) as client:
        first = client.search(
            filters,
            page=0,
            size=page_size,
            sort_by="basariSirasi",
            direction="ASC",
            smart_search=False,
        )
        programs.extend(first.content)
        print(
            f"YÖK Atlas: toplam={first.total_elements}, sayfa={first.total_pages}, "
            f"ilk_sayfa={len(first.content)}, size={page_size}"
        )
        for page_no in range(1, first.total_pages):
            page = client.search(
                filters,
                page=page_no,
                size=page_size,
                sort_by="basariSirasi",
                direction="ASC",
                smart_search=False,
            )
            programs.extend(page.content)
            if page_no % 10 == 0 or page_no + 1 == first.total_pages:
                print(f"YÖK Atlas sayfa {page_no + 1}/{first.total_pages}: {len(page.content)}")
            time.sleep(0.10)
    return programs


def stats_to_dict(prog: Any, stat: Any) -> dict[str, Any]:
    return {
        "year": safe_int(stat.year),
        "code": str(prog.kilavuz_kodu),
        "university": prog.universite_adi or "",
        "program": prog.birim_adi or prog.birim_grup_adi or "",
        "score_type": prog.puan_turu or "",
        "quota": safe_int(stat.kontenjan),
        "placed": safe_int(stat.yerlesen),
        "min_score": safe_float(stat.min_puan),
        "rank": safe_int(stat.basari_sirasi),
        "source_url": "https://yokatlas.yok.gov.tr/",
        "source_kind": "YÖK Atlas resmî tercih kılavuzu JSON API",
    }


def build_dataset(programs: list[Any]) -> dict[str, Any]:
    output: list[dict[str, Any]] = []
    seen_codes: set[str] = set()
    for prog in programs:
        code = str(prog.kilavuz_kodu)
        if code in seen_codes:
            continue
        seen_codes.add(code)
        history = [
            stats_to_dict(prog, stat)
            for stat in prog.all_years
            if safe_int(stat.year) in YEARS
        ]
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
            rank_volatility = round(
                math.sqrt(sum((x - avg) ** 2 for x in diffs) / len(diffs)), 2
            )

        score_trend = None
        if len(scores) >= 2:
            score_trend = round(scores[-1] - scores[0], 4)

        output.append(
            {
                "code": code,
                "university": prog.universite_adi or "",
                "program": prog.birim_adi or prog.birim_grup_adi or "",
                "programGroup": prog.birim_grup_adi or "",
                "scoreType": prog.puan_turu or "",
                "universityType": prog.universite_turu or "",
                "city": prog.il_adi or "",
                "district": prog.ilce_adi or "",
                "language": prog.ogrenim_dili_adi or "",
                "scholarship": prog.burs_orani_adi or "",
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

    output.sort(
        key=lambda item: (
            item["latestRank"] is None,
            item["latestRank"] if item["latestRank"] is not None else 10**12,
            item["university"],
            item["program"],
        )
    )
    ranked = sum(item["latestRank"] is not None for item in output)
    return {
        "schemaVersion": 3,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "years": YEARS,
        "source": "YÖK Atlas resmî tercih kılavuzu JSON API (2022-2025)",
        "sourceUrl": "https://yokatlas.yok.gov.tr/",
        "disclaimer": "2026 verisi içermez. Tahminler resmî sonuç değildir.",
        "programCount": len(output),
        "rankedProgramCount": ranked,
        "programs": output,
    }


def validate(data: dict[str, Any]) -> None:
    assert data["years"] == YEARS
    assert 2026 not in data["years"]
    assert data["programCount"] >= 5000, data["programCount"]
    assert data["rankedProgramCount"] >= 3000, data["rankedProgramCount"]
    samples = [p for p in data["programs"] if p["latestRank"] is not None]
    assert samples
    assert all(p["latestQuota"] is None or p["latestQuota"] >= 0 for p in samples[:1000])
    print(
        f"Doğrulandı: {data['programCount']} program, "
        f"{data['rankedProgramCount']} başarı sıralı program"
    )


def main() -> int:
    programs = fetch_all_programs()
    data = build_dataset(programs)
    validate(data)
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    PUBLIC_DIR.mkdir(parents=True, exist_ok=True)
    compact = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    pretty = json.dumps(data, ensure_ascii=False, indent=2)
    (ASSET_DIR / "programs.json").write_text(compact, encoding="utf-8")
    (PUBLIC_DIR / "programs.json").write_text(pretty, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

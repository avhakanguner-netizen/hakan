#!/usr/bin/env python3
"""ÖSYM'nin 2022-2025 YKS yerleştirme tablolarından Android veri seti üretir.

Kaynaklar resmî ÖSYM Excel dosyalarıdır. Program kodu esas alınır; kaynak satırları
olabildiğince kayıpsız tutulur. 2026 verisi bilerek alınmaz.
"""
from __future__ import annotations

import json
import math
import re
import sys
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

import pandas as pd
import requests

ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app" / "src" / "main" / "assets"
PUBLIC_DIR = ROOT / "public" / "data"

SOURCES = {
    2022: "https://dokuman.osym.gov.tr/pdfdokuman/2022/YKS/YERLESTIRME/yks_yerlestirme_tablo4_2022.xlsx",
    2023: "https://dokuman.osym.gov.tr/pdfdokuman/2023/YKS/YERLESTIRME/tablo4yd_22082023.xlsx",
    2024: "https://dokuman.osym.gov.tr/pdfdokuman/2024/YKS/YERLESTIRME/tablo-4minmax_b27082024.xlsx",
    2025: "https://dokuman.osym.gov.tr/pdfdokuman/2025/YKS/YERLEST%C4%B0RME/tablo4_ykd25082025.xlsx",
}

SCORE_TYPES = {"SAY", "EA", "SÖZ", "DİL", "TYT"}
CODE_RE = re.compile(r"^\d{8,10}$")
UNI_RE = re.compile(r"([A-ZÇĞİÖŞÜ0-9 .,'’()-]+ÜNİVERSİTESİ)", re.IGNORECASE)


@dataclass
class ProgramRow:
    year: int
    code: str
    university: str
    program: str
    score_type: str
    quota: int | None
    placed: int | None
    min_score: float | None
    max_score: float | None
    source_url: str
    source_kind: str = "ÖSYM resmî yerleştirme tablosu"


def clean(value: Any) -> str:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return ""
    text = str(value).replace("\n", " ").replace("\r", " ").strip()
    return re.sub(r"\s+", " ", text)


def number(value: Any) -> float | None:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    text = clean(value).replace(".", "").replace(",", ".")
    try:
        return float(text)
    except ValueError:
        return None


def integer(value: Any) -> int | None:
    num = number(value)
    if num is None or num < 0:
        return None
    rounded = int(round(num))
    return rounded if abs(num - rounded) < 0.001 else None


def find_header(df: pd.DataFrame) -> tuple[int, list[str]]:
    best_idx = 0
    best_score = -1
    best_labels: list[str] = []
    for idx in range(min(50, len(df))):
        labels = [clean(v) for v in df.iloc[idx].tolist()]
        joined = " | ".join(labels).lower()
        score = sum(
            term in joined
            for term in ("program kod", "üniversite", "program adı", "puan tür", "kontenjan", "en küçük", "en büyük")
        )
        if score > best_score:
            best_idx, best_score, best_labels = idx, score, labels
    return best_idx, best_labels


def header_map(labels: list[str]) -> dict[str, int]:
    mapping: dict[str, int] = {}
    for i, label in enumerate(labels):
        low = label.casefold()
        if "program" in low and "kod" in low:
            mapping.setdefault("code", i)
        elif "puan tür" in low:
            mapping.setdefault("score_type", i)
        elif "kontenjan" in low and "yerleş" not in low:
            mapping.setdefault("quota", i)
        elif "yerleşen" in low:
            mapping.setdefault("placed", i)
        elif "en küçük" in low:
            mapping.setdefault("min_score", i)
        elif "en büyük" in low:
            mapping.setdefault("max_score", i)
        elif ("program adı" in low or "yükseköğretim programı" in low) and "kod" not in low:
            mapping.setdefault("program", i)
        elif "üniversite" in low:
            mapping.setdefault("university", i)
    return mapping


def locate_code(values: list[str], mapping: dict[str, int]) -> tuple[str, int] | None:
    preferred = mapping.get("code")
    if preferred is not None and preferred < len(values):
        candidate = re.sub(r"\.0$", "", values[preferred])
        if CODE_RE.fullmatch(candidate):
            return candidate, preferred
    for i, value in enumerate(values):
        candidate = re.sub(r"\.0$", "", value)
        if CODE_RE.fullmatch(candidate):
            return candidate, i
    return None


def guess_score_type(values: list[str], mapping: dict[str, int]) -> str:
    idx = mapping.get("score_type")
    if idx is not None and idx < len(values):
        val = values[idx].upper()
        if val in SCORE_TYPES:
            return val
    for value in values:
        val = value.upper()
        if val in SCORE_TYPES:
            return val
    return ""


def text_value(values: list[str], mapping: dict[str, int], key: str) -> str:
    idx = mapping.get(key)
    return values[idx] if idx is not None and idx < len(values) else ""


def numeric_value(raw: list[Any], mapping: dict[str, int], key: str) -> float | None:
    idx = mapping.get(key)
    return number(raw[idx]) if idx is not None and idx < len(raw) else None


def extract_university(program_text: str, context: str, explicit: str) -> str:
    if explicit:
        return explicit
    match = UNI_RE.search(program_text)
    if match:
        return clean(match.group(1))
    match = UNI_RE.search(context)
    return clean(match.group(1)) if match else context


def fallback_program(values: list[str], code_idx: int, score_type: str) -> str:
    candidates = []
    for i, value in enumerate(values):
        if i == code_idx or not value or value.upper() == score_type:
            continue
        if CODE_RE.fullmatch(re.sub(r"\.0$", "", value)):
            continue
        if len(value) >= 3 and not re.fullmatch(r"[\d.,-]+", value):
            candidates.append(value)
    return max(candidates, key=len, default="")


def parse_sheet(df: pd.DataFrame, year: int, source_url: str) -> list[ProgramRow]:
    header_idx, labels = find_header(df)
    mapping = header_map(labels)
    result: list[ProgramRow] = []
    context = ""

    for row_idx in range(header_idx + 1, len(df)):
        raw = df.iloc[row_idx].tolist()
        values = [clean(v) for v in raw]
        nonempty = [v for v in values if v]
        if not nonempty:
            continue

        located = locate_code(values, mapping)
        if located is None:
            joined = " ".join(nonempty)
            if "ÜNİVERSİTESİ" in joined.upper() and len(joined) < 240:
                context = joined
            continue

        code, code_idx = located
        score_type = guess_score_type(values, mapping)
        program = text_value(values, mapping, "program") or fallback_program(values, code_idx, score_type)
        explicit_uni = text_value(values, mapping, "university")
        university = extract_university(program, context, explicit_uni)

        quota = integer(numeric_value(raw, mapping, "quota"))
        placed = integer(numeric_value(raw, mapping, "placed"))
        min_score = numeric_value(raw, mapping, "min_score")
        max_score = numeric_value(raw, mapping, "max_score")

        # Başlıklar birleşik geldiğinde sayısal alanları sondan güvenli biçimde bul.
        numeric_cells = [number(v) for v in raw]
        plausible_scores = [v for v in numeric_cells if v is not None and 100 <= v <= 600]
        if min_score is None and plausible_scores:
            min_score = plausible_scores[-2] if len(plausible_scores) >= 2 else plausible_scores[-1]
        if max_score is None and plausible_scores:
            max_score = plausible_scores[-1]

        if not program:
            continue
        result.append(
            ProgramRow(
                year=year,
                code=code,
                university=university,
                program=program,
                score_type=score_type,
                quota=quota,
                placed=placed,
                min_score=round(min_score, 5) if min_score is not None else None,
                max_score=round(max_score, 5) if max_score is not None else None,
                source_url=source_url,
            )
        )
    return result


def download_excel(year: int, url: str, temp_dir: Path) -> Path:
    path = temp_dir / f"osym_yks_{year}_tablo4.xlsx"
    response = requests.get(url, timeout=120, headers={"User-Agent": "TercihRadari/1.0"})
    response.raise_for_status()
    path.write_bytes(response.content)
    return path


def load_year(year: int, url: str, temp_dir: Path) -> list[ProgramRow]:
    path = download_excel(year, url, temp_dir)
    sheets = pd.read_excel(path, sheet_name=None, header=None, engine="openpyxl")
    rows: list[ProgramRow] = []
    for df in sheets.values():
        rows.extend(parse_sheet(df, year, url))
    # Aynı program kodu/yıl birden fazla sayfada görünürse ilk dolu kaydı koru.
    unique: dict[tuple[int, str], ProgramRow] = {}
    for row in rows:
        key = (row.year, row.code)
        old = unique.get(key)
        if old is None or (old.min_score is None and row.min_score is not None):
            unique[key] = row
    return list(unique.values())


def add_history_and_metrics(rows: list[ProgramRow]) -> dict[str, Any]:
    grouped: dict[str, list[ProgramRow]] = {}
    for row in rows:
        grouped.setdefault(row.code, []).append(row)

    programs = []
    for code, history in grouped.items():
        history.sort(key=lambda item: item.year)
        latest = history[-1]
        scores = [item.min_score for item in history if item.min_score is not None]
        trend = None
        volatility = None
        if len(scores) >= 2:
            trend = round(scores[-1] - scores[0], 4)
            diffs = [scores[i] - scores[i - 1] for i in range(1, len(scores))]
            mean = sum(diffs) / len(diffs)
            volatility = round(math.sqrt(sum((d - mean) ** 2 for d in diffs) / len(diffs)), 4)
        programs.append(
            {
                "code": code,
                "university": latest.university,
                "program": latest.program,
                "scoreType": latest.score_type,
                "latestYear": latest.year,
                "latestMinScore": latest.min_score,
                "latestMaxScore": latest.max_score,
                "latestQuota": latest.quota,
                "latestPlaced": latest.placed,
                "scoreTrend": trend,
                "volatility": volatility,
                "history": [asdict(item) for item in history],
            }
        )

    programs.sort(key=lambda item: (item.get("university") or "", item.get("program") or ""))
    return {
        "schemaVersion": 2,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "years": sorted(SOURCES),
        "source": "ÖSYM resmî 2022-2025 YKS merkezî yerleştirme Tablo-4 Excel dosyaları",
        "disclaimer": "2026 verisi içermez. Tahminler resmî sonuç değildir.",
        "programCount": len(programs),
        "programs": programs,
    }


def main() -> int:
    import tempfile

    all_rows: list[ProgramRow] = []
    with tempfile.TemporaryDirectory() as temp:
        temp_dir = Path(temp)
        for year, url in SOURCES.items():
            print(f"{year} indiriliyor: {url}")
            rows = load_year(year, url, temp_dir)
            print(f"{year}: {len(rows)} lisans programı ayrıştırıldı")
            if len(rows) < 500:
                raise RuntimeError(f"{year} için beklenenden az kayıt bulundu: {len(rows)}")
            all_rows.extend(rows)

    dataset = add_history_and_metrics(all_rows)
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    PUBLIC_DIR.mkdir(parents=True, exist_ok=True)
    compact = json.dumps(dataset, ensure_ascii=False, separators=(",", ":"))
    pretty = json.dumps(dataset, ensure_ascii=False, indent=2)
    (ASSET_DIR / "programs.json").write_text(compact, encoding="utf-8")
    (PUBLIC_DIR / "programs.json").write_text(pretty, encoding="utf-8")
    print(f"Toplam benzersiz program: {dataset['programCount']}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"VERİ ÜRETİM HATASI: {exc}", file=sys.stderr)
        raise

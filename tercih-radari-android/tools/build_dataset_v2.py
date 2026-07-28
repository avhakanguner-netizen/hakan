#!/usr/bin/env python3
"""Üniversite adı alanını güvenli biçimde ayrıştıran Tercih Radarı veri üreticisi."""
from __future__ import annotations

import json
from pathlib import Path

import build_dataset as base

CATEGORY_VALUES = {
    "DEVLET", "VAKIF", "KKTC", "YABANCI", "ÖZEL", "ÜCRETLİ",
    "TÜRKİYE", "KIBRIS", "DİĞER",
}


def fixed_header_map(labels: list[str]) -> dict[str, int]:
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
        # "Üniversite Türü" (DEVLET/VAKIF) üniversite adı değildir.
        elif (
            "üniversite adı" in low
            or "yükseköğretim kurumu adı" in low
            or "yükseköğretim kurumunun adı" in low
        ):
            mapping["university"] = i
    return mapping


def fixed_extract_university(program_text: str, context: str, explicit: str) -> str:
    explicit = base.clean(explicit)
    if explicit and explicit.upper() not in CATEGORY_VALUES:
        match = base.UNI_RE.search(explicit)
        if match:
            return base.clean(match.group(1))
        if "ÜNİVERSİTE" in explicit.upper() and len(explicit) > 8:
            return explicit
    match = base.UNI_RE.search(program_text)
    if match:
        return base.clean(match.group(1))
    match = base.UNI_RE.search(context)
    if match:
        return base.clean(match.group(1))
    return base.clean(context)


def validate_university_names() -> None:
    path = base.ASSET_DIR / "programs.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    invalid = [
        item for item in data["programs"]
        if not item.get("university")
        or item.get("university", "").upper() in CATEGORY_VALUES
    ]
    ratio = len(invalid) / max(1, data["programCount"])
    print(f"Geçersiz/boş üniversite adı: {len(invalid)} / {data['programCount']} (%{ratio*100:.2f})")
    if ratio > 0.03:
        examples = [(x.get("code"), x.get("university"), x.get("program")) for x in invalid[:10]]
        raise RuntimeError(f"Üniversite adı kalite eşiği aşıldı: {examples}")


if __name__ == "__main__":
    base.header_map = fixed_header_map
    base.extract_university = fixed_extract_university
    status = base.main()
    validate_university_names()
    raise SystemExit(status)

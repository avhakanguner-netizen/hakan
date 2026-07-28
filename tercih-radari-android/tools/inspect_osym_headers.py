#!/usr/bin/env python3
from pathlib import Path
import tempfile
import pandas as pd
import requests
from build_dataset import SOURCES, clean

with tempfile.TemporaryDirectory() as td:
    td=Path(td)
    for year,url in SOURCES.items():
        r=requests.get(url,timeout=120,headers={'User-Agent':'TercihRadari/1.1'})
        r.raise_for_status()
        p=td/f'{year}.xlsx'; p.write_bytes(r.content)
        sheets=pd.read_excel(p,sheet_name=None,header=None,engine='openpyxl')
        print(f'=== YEAR {year} ===')
        for name,df in sheets.items():
            for idx in range(min(60,len(df))):
                vals=[clean(v) for v in df.iloc[idx].tolist()]
                joined=' | '.join(v for v in vals if v)
                low=joined.casefold()
                if any(k in low for k in ('başarı','sıra','en küçük','kontenjan','program kod')):
                    print(f'SHEET={name!r} ROW={idx}: {joined[:2000]}')

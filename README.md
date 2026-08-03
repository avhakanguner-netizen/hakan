# Kütüphane Uygulaması

Komut satırından çalışan bu araç kitap kayıtlarını JSON dosyasında saklar.
Kitap adı, yazar, yayınevi, basım yılı, konu ve raf/sıra bilgisi gibi
alanlarla kayıt ekleyebilir, listeleyebilir ve çok kriterli arama yapabilirsiniz.

## Gereksinimler
- Python 3.10 veya daha yeni bir sürüm

## Kurulum
Depo kökünde hiçbir ek bağımlılık yoktur. Komutları doğrudan çalıştırabilirsiniz:

```bash
python library.py --help
```

İlk çalıştırmada `library_data.json` dosyası beş örnek kayıtla oluşturulur;
siz de bu dosyayı sürdürerek veya `add` komutuyla yeni kayıtlar ekleyebilirsiniz.

## Kullanım

### Tüm kitapları listeleme
```bash
python library.py list
```

### Kriterlere göre arama/süzme
Alt komut olarak `search` veya `filter` kullanılabilir. Tüm argümanlar isteğe
bağlıdır ve birlikte kullanılabilir.

```bash
# Yazar ve konuya göre arama
python library.py search --author "dostoyevski" --topic "roman"

# Basım yılı ve raf bilgisine göre süzme
python library.py filter --year 2020 --location "Raf A"
```

Kullanılabilir filtreler:
- `--title` : Kitap adında arama
- `--author` : Yazar adında arama
- `--publisher` : Yayınevi adında arama
- `--year` : Basım yılı (tam eşleşme)
- `--topic` : Konu/tema içinde arama
- `--location` : Raf/sıra bilgisinde arama

### Yeni kitap ekleme
```bash
python library.py add \
  --title "Yeni Kitap" \
  --author "Yazar Adı" \
  --publisher "Yayınevi" \
  --year 2024 \
  --topic "Konu" \
  --location "Raf B - Sıra 2"
```

Komut başarıyla çalıştığında eklenen kayıt ekranda gösterilir ve
`library_data.json` dosyasına kaydedilir.

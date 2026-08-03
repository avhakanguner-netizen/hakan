"""Basit bir kütüphane yönetim aracı.

Bu komut satırı aracı kitap kayıtlarını JSON dosyasında saklar, yeni kayıt
ekler ve çok kriterli arama/süzme yapar.
"""
from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, List, Optional

DATA_FILE = Path(__file__).with_name("library_data.json")


@dataclass
class BookRecord:
    """Kütüphanedeki tek bir kitabın temel bilgileri."""

    title: str
    author: str
    publisher: str
    year: int
    topic: str
    location: str


class Library:
    """Kitap kayıtlarını yöneten basit depolama sınıfı."""

    def __init__(self, data_file: Path = DATA_FILE) -> None:
        self.data_file = data_file
        self.books: List[BookRecord] = []
        self._load_data()

    def _load_data(self) -> None:
        if not self.data_file.exists():
            self._seed_initial_data()
        with self.data_file.open("r", encoding="utf-8") as f:
            raw = json.load(f)
        self.books = [BookRecord(**item) for item in raw]

    def _seed_initial_data(self) -> None:
        sample_books = [
            {
                "title": "Suç ve Ceza",
                "author": "Fyodor Dostoyevski",
                "publisher": "İş Bankası Kültür",
                "year": 2022,
                "topic": "Klasik Roman",
                "location": "Raf A - Sıra 3",
            },
            {
                "title": "Körlük",
                "author": "José Saramago",
                "publisher": "Kırmızı Kedi",
                "year": 2021,
                "topic": "Distopya",
                "location": "Raf B - Sıra 1",
            },
            {
                "title": "Bir Ömür Nasıl Yaşanır?",
                "author": "İlber Ortaylı",
                "publisher": "Kronik Kitap",
                "year": 2019,
                "topic": "Deneme",
                "location": "Raf C - Sıra 4",
            },
            {
                "title": "Simyacı",
                "author": "Paulo Coelho",
                "publisher": "Can Yayınları",
                "year": 2020,
                "topic": "Roman",
                "location": "Raf A - Sıra 5",
            },
            {
                "title": "Veri Yapıları ve Algoritmalar",
                "author": "Cormen, Leiserson, Rivest, Stein",
                "publisher": "Palme Yayıncılık",
                "year": 2016,
                "topic": "Bilgisayar Bilimi",
                "location": "Raf D - Sıra 2",
            },
        ]
        with self.data_file.open("w", encoding="utf-8") as f:
            json.dump(sample_books, f, ensure_ascii=False, indent=2)

    def save(self) -> None:
        with self.data_file.open("w", encoding="utf-8") as f:
            json.dump([asdict(book) for book in self.books], f, ensure_ascii=False, indent=2)

    def add_book(
        self,
        *,
        title: str,
        author: str,
        publisher: str,
        year: int,
        topic: str,
        location: str,
    ) -> BookRecord:
        book = BookRecord(
            title=title,
            author=author,
            publisher=publisher,
            year=year,
            topic=topic,
            location=location,
        )
        self.books.append(book)
        self.save()
        return book

    def find_books(
        self,
        *,
        title: Optional[str] = None,
        author: Optional[str] = None,
        publisher: Optional[str] = None,
        year: Optional[int] = None,
        topic: Optional[str] = None,
        location: Optional[str] = None,
    ) -> List[BookRecord]:
        def normalize(text: str) -> str:
            return text.casefold()

        def matches(book: BookRecord) -> bool:
            if title and normalize(title) not in normalize(book.title):
                return False
            if author and normalize(author) not in normalize(book.author):
                return False
            if publisher and normalize(publisher) not in normalize(book.publisher):
                return False
            if topic and normalize(topic) not in normalize(book.topic):
                return False
            if location and normalize(location) not in normalize(book.location):
                return False
            if year is not None and year != book.year:
                return False
            return True

        return [book for book in self.books if matches(book)]

    def list_books(self) -> List[BookRecord]:
        return sorted(self.books, key=lambda book: (book.author, book.title))


def add_common_filters(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--title", help="Kitap adı içinde arama yap")
    parser.add_argument("--author", help="Yazar adı içinde arama yap")
    parser.add_argument("--publisher", help="Yayınevi adı içinde arama yap")
    parser.add_argument("--year", type=int, help="Basım yılı (tam eşleşme)")
    parser.add_argument("--topic", help="Konu/tema içinde arama yap")
    parser.add_argument("--location", help="Raf/sıra bilgisi içinde arama yap")


def print_books(books: Iterable[BookRecord]) -> None:
    for index, book in enumerate(books, start=1):
        print(
            f"[{index}] {book.title} — {book.author}\n"
            f"    Yayınevi: {book.publisher} | Basım yılı: {book.year}\n"
            f"    Konu: {book.topic} | Yer: {book.location}\n"
        )


def handle_add(library: Library, args: argparse.Namespace) -> None:
    book = library.add_book(
        title=args.title,
        author=args.author,
        publisher=args.publisher,
        year=args.year,
        topic=args.topic,
        location=args.location,
    )
    print("Yeni kitap eklendi:\n")
    print_books([book])


def handle_list(library: Library, _: argparse.Namespace) -> None:
    print_books(library.list_books())


def handle_search(library: Library, args: argparse.Namespace) -> None:
    books = library.find_books(
        title=args.title,
        author=args.author,
        publisher=args.publisher,
        year=args.year,
        topic=args.topic,
        location=args.location,
    )
    if books:
        print_books(books)
    else:
        print("Eşleşen kitap bulunamadı.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Kitap adı, yazar, yayınevi, basım yılı, konu ve yer bilgisiyle "
            "süzülebilen basit kütüphane aracıdır."
        )
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    add_parser = subparsers.add_parser("add", help="Yeni kitap ekle")
    add_parser.add_argument("--title", required=True, help="Kitap adı")
    add_parser.add_argument("--author", required=True, help="Yazar adı")
    add_parser.add_argument("--publisher", required=True, help="Yayınevi")
    add_parser.add_argument("--year", required=True, type=int, help="Basım yılı")
    add_parser.add_argument("--topic", required=True, help="Konu/tema")
    add_parser.add_argument("--location", required=True, help="Raf/sıra bilgisi")
    add_parser.set_defaults(func=handle_add)

    list_parser = subparsers.add_parser("list", help="Tüm kitapları listele")
    list_parser.set_defaults(func=handle_list)

    search_parser = subparsers.add_parser(
        "search", help="Çok kriterli arama ve süzme yap"
    )
    add_common_filters(search_parser)
    search_parser.set_defaults(func=handle_search)

    list_filter_parser = subparsers.add_parser(
        "filter", help="Listeleme sırasında filtre uygula"
    )
    add_common_filters(list_filter_parser)
    list_filter_parser.set_defaults(func=handle_search)

    return parser


def main(argv: Optional[List[str]] = None) -> None:
    parser = build_parser()
    args = parser.parse_args(argv)
    library = Library()
    args.func(library, args)


if __name__ == "__main__":
    main()

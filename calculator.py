#!/usr/bin/env python3
"""
Basit bir hesap makinesi uygulaması.

Bu modül, dört temel aritmetik işlemi destekleyen bir `calculate` fonksiyonu
sağlar. Komut satırından çağrıldığında kullanıcıdan giriş alarak işlemi yapar.
"""

from __future__ import annotations


def calculate(a: float, b: float, operator: str) -> float:
    """Verilen iki sayı ve operatör ile işlemi yapar.

    Args:
        a: Birinci sayı.
        b: İkinci sayı.
        operator: '+', '-', '*', '/' operatörlerinden biri.

    Returns:
        İşlem sonucu.

    Raises:
        ValueError: Geçersiz operatör girilirse.
        ZeroDivisionError: Bölme işleminde payda sıfırsa.
    """
    if operator == '+':
        return a + b
    if operator == '-':
        return a - b
    if operator == '*':
        return a * b
    if operator == '/':
        return a / b
    raise ValueError(f"Geçersiz operatör: {operator}")


if __name__ == "__main__":
    try:
        x = float(input("Birinci sayı: "))
        op = input("Operatör (+, -, *, /): ")
        y = float(input("İkinci sayı: "))
        result = calculate(x, y, op)
    except Exception as exc:  # pylint: disable=broad-except
        print(f"Hata: {exc}")
    else:
        print(f"Sonuç: {result}")

"""Exemplo de Python EXTERNO (sem nenhuma dependencia Nekkora): le um CSV e produz um resumo.

Uso:
    python relatorio.py --entrada vendas.csv --saida resumo.txt [--efeitos efeitos.txt] [--dormir 0] [--falhar] [--sinalizar]

* --efeitos: acrescenta UMA linha por execucao (efeito verificavel e contavel);
* --dormir N: espera N segundos antes de terminar (execucao longa);
* --falhar: escreve o efeito e sai com codigo 3 (erro sinalizado pelo codigo de saida);
* --sinalizar: se o lancador do Agent definiu NEKKORA_SINAL, grava ali o sucesso de negocio (opcional; o script roda
  igual sem o Agent).
Qualquer outro argumento e aceito e ignorado (o teste de vazamento usa um valor sentinela aqui).
"""
import argparse
import csv
import json
import os
import sys
import time
from datetime import datetime, timezone


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--entrada", required=True)
    ap.add_argument("--saida", required=True)
    ap.add_argument("--efeitos")
    ap.add_argument("--dormir", type=float, default=0)
    ap.add_argument("--falhar", action="store_true")
    ap.add_argument("--sinalizar", action="store_true")
    a, _ignorados = ap.parse_known_args()

    total, linhas = 0.0, 0
    with open(a.entrada, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            total += float(row["valor"])
            linhas += 1
    with open(a.saida, "w", encoding="utf-8") as f:
        f.write(f"linhas={linhas}\ntotal={total:.2f}\n")
    if a.efeitos:
        with open(a.efeitos, "a", encoding="utf-8") as f:
            f.write(f"{datetime.now(timezone.utc).isoformat()} pid={os.getpid()} linhas={linhas}\n")
    print(f"resumo gravado: {linhas} linhas")
    if a.dormir > 0:
        time.sleep(a.dormir)
    if a.falhar:
        print("falha sinalizada pelo codigo de saida", file=sys.stderr)
        return 3
    sinal = os.environ.get("NEKKORA_SINAL")
    if a.sinalizar and sinal:
        with open(sinal, "w", encoding="utf-8") as f:
            json.dump({"schema": "nekkora-agent/external-signal", "version": "1.0.0", "outcome": "BUSINESS_SUCCESS"}, f)
    return 0


if __name__ == "__main__":
    sys.exit(main())

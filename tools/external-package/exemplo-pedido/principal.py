"""Exemplo de pacote Python EXTERNO para pedido de execucao pela Platform (sem nenhuma dependencia Nekkora).

Roda SEM argumentos: le o CSV de exemplo que veio no pacote (dados_exemplo/vendas.csv, sem dados reais) e grava o resumo
na pasta de saida que o Agent informa em NEKKORA_SAIDA. Acrescenta UMA linha em ../efeitos.txt (na pasta de saida escolhida
na maquina) por execucao: efeito verificavel e contavel. Se NEKKORA_SINAL existir, sinaliza o sucesso de negocio.
"""
import csv
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

from resumo import total_por_cliente


def main() -> int:
    saida = Path(os.environ.get("NEKKORA_SAIDA", "."))
    saida.mkdir(parents=True, exist_ok=True)
    with open(Path(__file__).with_name("dados_exemplo") / "vendas.csv", newline="", encoding="utf-8") as f:
        linhas = list(csv.DictReader(f))
    totais = total_por_cliente(linhas)
    (saida / "resumo.json").write_text(json.dumps(totais, ensure_ascii=False, indent=2), encoding="utf-8")
    with open(saida.parent / "efeitos.txt", "a", encoding="utf-8") as f:
        f.write(f"{datetime.now(timezone.utc).isoformat()} execucao={saida.name} clientes={len(totais)}\n")
    sinal = os.environ.get("NEKKORA_SINAL")
    if sinal:
        Path(sinal).write_text(json.dumps({"schema": "nekkora-agent/external-signal", "version": "1.0.0", "outcome": "BUSINESS_SUCCESS"}), encoding="utf-8")
    print(f"resumo de {len(linhas)} linhas")
    return 0


if __name__ == "__main__":
    sys.exit(main())

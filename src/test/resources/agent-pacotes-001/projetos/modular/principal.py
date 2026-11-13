"""Fixture AGENT-PACOTES-001: projeto com modulos e dados em subpastas (sem dados reais).

Le config/parametros.json ao lado deste arquivo, soma os valores com o modulo calculos.totais e grava o resultado na pasta
que o Agent informa em NEKKORA_SAIDA. Tambem grava saida-relativa.txt pelo caminho RELATIVO, para mostrar onde fica a pasta
de trabalho (a copia conferida do pacote), e nao a pasta de saida.
"""
import json
import os
import sys
from pathlib import Path

from calculos.totais import somar


def main() -> int:
    base = Path(__file__).resolve().parent
    parametros = json.loads((base / "config" / "parametros.json").read_text(encoding="utf-8"))
    total = somar(parametros["valores"])
    saida = Path(os.environ.get("NEKKORA_SAIDA", "."))
    saida.mkdir(parents=True, exist_ok=True)
    (saida / "resultado.json").write_text(json.dumps({"total": total, "cwd": os.getcwd()}), encoding="utf-8")
    Path("saida-relativa.txt").write_text("gravado pelo caminho relativo\n", encoding="utf-8")
    print(f"total={total}")
    return int(parametros.get("codigo_de_saida", 0))


if __name__ == "__main__":
    sys.exit(main())

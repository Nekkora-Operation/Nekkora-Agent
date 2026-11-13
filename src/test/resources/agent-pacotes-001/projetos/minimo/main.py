"""Fixture AGENT-PACOTES-001: projeto minimo, sem dependencias externas e sem dados reais."""
import os
import sys
from pathlib import Path


def main() -> int:
    saida = Path(os.environ.get("NEKKORA_SAIDA", "."))
    saida.mkdir(parents=True, exist_ok=True)
    (saida / "ola.txt").write_text("ola do projeto minimo\n", encoding="utf-8")
    print("projeto minimo concluido")
    return 0


if __name__ == "__main__":
    sys.exit(main())

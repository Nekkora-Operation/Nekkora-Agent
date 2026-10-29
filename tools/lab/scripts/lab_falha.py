"""Laboratório: publica FAILED (regra de negócio fictícia recusou) e sai com código 1."""

import sys

from lab_comum import SENTINELA, resultado, saida

pasta = saida()
print(f"recusado: {SENTINELA}", file=sys.stderr, flush=True)
resultado(pasta, "FAILED", ITEMS=1, REJECTED=1)
sys.exit(1)

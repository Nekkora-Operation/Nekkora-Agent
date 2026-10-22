"""Laboratório: despeja ~16 MiB na saída e ~16 MiB nos erros (o Agent precisa drenar sem travar e sem guardar)."""

import sys

from lab_comum import SENTINELA, resultado, saida

pasta = saida()
linha = (SENTINELA + " ") * 50 + "\n"
for _ in range(10_000):
    sys.stdout.write(linha)
    sys.stderr.write(linha)
sys.stdout.flush()
sys.stderr.flush()
resultado(pasta, "SUCCEEDED", ITEMS=1)

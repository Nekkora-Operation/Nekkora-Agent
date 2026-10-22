"""Laboratório: processa 3 itens fictícios, imprime valores (o Agent não guarda) e publica SUCCEEDED."""

import time

from lab_comum import SENTINELA, resultado, saida

pasta = saida()
for i in range(3):
    print(f"item {i}: {SENTINELA}", flush=True)
    time.sleep(0.5)
resultado(pasta, "SUCCEEDED", ITEMS=3)

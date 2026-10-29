"""Laboratório de efeito NÃO IDEMPOTENTE: anota um "envio" em ``efeitos.txt`` (na pasta-mãe da saída, compartilhada
entre execuções), espera 120 s e só então publica o resultado. Se o Agent repetisse a execução sozinho depois de um
reinício ou de uma suspensão, apareceria um segundo envio.
"""

import time
from pathlib import Path

from lab_comum import resultado, saida

pasta = saida()
efeitos = Path(pasta).parent / "efeitos.txt"
with efeitos.open("a", encoding="utf-8") as f:
    f.write("envio\n")
time.sleep(120)
resultado(pasta, "SUCCEEDED", ITEMS=1)

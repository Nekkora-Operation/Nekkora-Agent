"""Laboratório de efeito NÃO IDEMPOTENTE e curto (F-AGT-01): anota UM "envio" em ``efeitos.txt`` (pasta-mãe da saída),
espera ~2 s e publica SUCCEEDED. Uma repetição da execução apareceria como segunda linha.
"""

import time

from lab_comum import efeito_unico, resultado, saida

pasta = saida()
efeito_unico(pasta)
time.sleep(2)
resultado(pasta, "SUCCEEDED", ITEMS=1)

"""Laboratório de SESSÃO (G2/A13): um efeito não idempotente, ~6 min acordada, publica FAILED e sai com código 1.

O protocolo prova a falha (regra de negócio fictícia): FAILED mesmo com bloqueio e suspensão no meio.
"""

import sys

from lab_comum import efeito_unico, esperar_acordado, resultado, saida

pasta = saida()
efeito_unico(pasta)
esperar_acordado(360)
resultado(pasta, "FAILED", ITEMS=1, REJECTED=1)
sys.exit(1)

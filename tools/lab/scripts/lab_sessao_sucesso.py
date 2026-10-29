"""Laboratório de SESSÃO (G2/A13): um efeito não idempotente, ~6 min acordada e resultado SUCCEEDED publicado.

Bloqueio e suspensão no meio: o protocolo prova o sucesso; o Agent registra os dois fatos e não repete nada.
"""

from lab_comum import efeito_unico, esperar_acordado, resultado, saida

pasta = saida()
efeito_unico(pasta)
esperar_acordado(360)
resultado(pasta, "SUCCEEDED", ITEMS=1)

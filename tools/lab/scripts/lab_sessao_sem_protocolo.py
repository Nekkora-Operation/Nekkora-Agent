"""Laboratório de SESSÃO (G2/A13): um efeito não idempotente, ~6 min acordada e saída 0 SEM publicar resultado.

Com suspensão no meio e nenhuma prova estruturada, o Agent não presume nada: resultado desconhecido (AGT-EXE-026).
"""

from lab_comum import efeito_unico, esperar_acordado, saida

pasta = saida()
efeito_unico(pasta)
esperar_acordado(360)
print("terminei, mas não disse como", flush=True)

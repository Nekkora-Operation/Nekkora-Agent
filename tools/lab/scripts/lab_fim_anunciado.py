"""Laboratório (D-SA-04): anuncia o próprio fim em ``fim-anunciado`` na pasta de saída, espera 0,8 s, publica SUCCEEDED e
termina com código 0. Um cancelamento pedido DEPOIS do anúncio chega quando o processo já está terminando por conta
própria: o Agent registra o pedido como não efetivado e o desfecho fica o do protocolo.
"""

import time

from lab_comum import resultado, saida

pasta = saida()
(pasta / "fim-anunciado").write_text("1", encoding="utf-8")
time.sleep(0.8)
resultado(pasta, "SUCCEEDED", ITEMS=1)

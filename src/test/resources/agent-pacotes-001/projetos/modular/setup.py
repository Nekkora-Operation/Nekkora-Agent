# Sentinela AGENT-PACOTES-001: se este arquivo for EXECUTADO, cria SENTINELA-EXECUTOU.txt ao lado dele.
# A importacao e o empacotamento nunca o executam; os testes conferem que o arquivo nao aparece.
from pathlib import Path

Path(__file__).with_name("SENTINELA-EXECUTOU.txt").write_text("setup.py executou", encoding="utf-8")

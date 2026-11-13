# Sentinela AGENT-PACOTES-001 (gancho de ferramenta): se for EXECUTADO, cria SENTINELA-EXECUTOU.txt ao lado dele.
from pathlib import Path

Path(__file__).with_name("SENTINELA-EXECUTOU.txt").write_text("conftest.py executou", encoding="utf-8")

"""Apoio dos processos de LABORATÓRIO do Nekkora Agent (DESKTOP-001). Não importa nekkora_rpa: prova que o supervisor
do Agent é independente do framework. Cada script recebe só ``--output <pasta>`` e publica ``agent-result.json``
(``nekkora-agent/lab-result`` 1.0.0) quando o protocolo se aplica. Dados FICTÍCIOS.
"""

import json
import sys
from pathlib import Path

SENTINELA = "SENTINELA-VALOR-DA-AUTOMACAO-7Q2"  # nunca pode aparecer no registro, nos eventos nem no diagnóstico do Agent


def saida() -> Path:
    args = sys.argv[1:]
    if len(args) != 2 or args[0] != "--output":
        print("uso: --output <pasta>", file=sys.stderr)
        sys.exit(64)
    pasta = Path(args[1])
    pasta.mkdir(parents=True, exist_ok=True)
    return pasta


def resultado(pasta: Path, status: str, **contagens: int) -> None:
    doc = {"schema": "nekkora-agent/lab-result", "version": "1.0.0", "status": status, "counts": contagens}
    (pasta / "agent-result.json").write_text(json.dumps(doc), encoding="utf-8")


def efeito_unico(pasta: Path) -> None:
    """Anota UM "envio" em ``efeitos.txt`` na pasta-mãe da saída (compartilhada): repetição apareceria como 2ª linha."""
    with (Path(pasta).parent / "efeitos.txt").open("a", encoding="utf-8") as f:
        f.write("envio\n")


def esperar_acordado(segundos: int) -> None:
    """Espera ``segundos`` de máquina ACORDADA (passos de 1 s): bloqueio e suspensão no meio não encurtam a execução."""
    import time

    for _ in range(segundos):
        time.sleep(1)

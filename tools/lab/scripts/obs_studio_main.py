"""OBS-001 (laboratório): ``main.py`` no FORMATO de um projeto do Studio que publica eventos REAIS do Framework.

Os eventos válidos saem do próprio ``nekkora_rpa`` (``EventLogger`` + ``JsonlEventSink``, ``nekkora-rpa/events``
1.2.0) em ``<--output>/execucao/events.jsonl``, como o código gerado faz; o resultado vai para
``execucao/summary.json``. Configuração em ``obs_config.json`` (copiado e conferido com o pacote):

* ``mode = "sentinel"``: além dos eventos reais, escreve DIRETAMENTE no mesmo arquivo linhas hostis (campos livres com
  CPF, e-mail, token, senha, caminho de cliente, valor de planilha, URL com consulta, traceback; campos desconhecidos;
  JSON aninhado, enorme e corrompido) e despeja os mesmos sentinelas em stdout/stderr — nada disso pode chegar à
  atividade, ao histórico, à tela ou à exportação do Agent;
* ``mode = "slow"``: ``items`` transações com ``delay_ms`` entre ações (queda/adoção/suspensão no meio).

Dados FICTÍCIOS. Não abre navegador nem rede.
"""

import json
import sys
import time
import uuid
from pathlib import Path

from nekkora_rpa.observability.events import EventCode, Phase
from nekkora_rpa.observability.logger import EventLogger, JsonlEventSink

HERE = Path(__file__).resolve().parent
CFG = json.loads((HERE / "obs_config.json").read_text(encoding="utf-8"))
SENTINELAS = [
    "123.456.789-09",
    "12345678909",
    "12.345.678/0001-95",
    "maria.sigilosa@cliente.example",
    "tok_SIGILOSO_OBS_9Z",
    "Senha#Sigilosa9Z",
    "C:\\Users\\ClienteSigiloso9Z\\Planilhas\\clientes.xlsx",
    "VALOR-EXCEL-9Z-4471",
    "https://cliente.example/api?token=abc9Z",
    "Traceback (most recent call last)",
]
STEPS = [("search.fill_search_term", "fill"), ("search.press_enter", "press"), ("profile.read_birth_date", "read_items")]


def main() -> int:
    args = sys.argv[1:]
    out = Path(args[args.index("--output") + 1])
    run = out / "execucao"
    run.mkdir(parents=True, exist_ok=True)
    events = run / "events.jsonl"
    mode = CFG.get("mode", "slow")
    items = int(CFG.get("items", 2))
    delay = int(CFG.get("delay_ms", 0)) / 1000.0
    if mode == "sentinel":
        for s in SENTINELAS:
            print(s)
            print(s, file=sys.stderr)
    log = EventLogger("ex-" + uuid.uuid4().hex[:20], "0.3.0", [JsonlEventSink(events)])
    log.emit(EventCode.ENGINE_STARTED)
    for ph in (Phase.INIT, Phase.TRANSFER_DATA_IN):
        log.emit(EventCode.PHASE_STARTED, phase=ph)
        log.emit(EventCode.PHASE_COMPLETED, phase=ph)
    log.emit(EventCode.PHASE_STARTED, phase=Phase.PROCESS)
    for i in range(items):
        tx = "tx-%020x" % (i + 1)
        log.emit(EventCode.TRANSACTION_STARTED, phase=Phase.PROCESS, transaction_id=tx, attempt=1)
        for step, action in STEPS:
            log.emit(EventCode.ACTION_STARTED, phase=Phase.PROCESS, transaction_id=tx, step_id=step, action=action)
            t = time.monotonic()
            time.sleep(delay)
            log.emit(
                EventCode.ACTION_COMPLETED,
                phase=Phase.PROCESS,
                transaction_id=tx,
                step_id=step,
                action=action,
                attempt=1,
                elapsed_ms=int((time.monotonic() - t) * 1000),
                outcome="OK",
            )
        if mode == "sentinel":
            hostis(events, log.execution_id)
        log.emit(EventCode.TRANSACTION_COMPLETED, phase=Phase.TRANSFER_DATA_OUT, transaction_id=tx, attempt=1, outcome="SUCCEEDED")
    log.emit(EventCode.PHASE_STARTED, phase=Phase.END)
    log.emit(EventCode.ENGINE_COMPLETED, phase=Phase.END, outcome="COMPLETED", count=items)
    log.close()
    summary = {"schema": "nekkora-rpa/run-summary", "version": "1.2.0", "execution_id": log.execution_id, "status": "COMPLETED",
               "counts": {"SUCCEEDED": items}, "output": {"status": "COMMITTED", "rows": items, "detail": None}}
    (run / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
    return 0


def hostis(events: Path, fw_id: str) -> None:
    """Linhas que um produtor com defeito (ou malicioso) poderia escrever no mesmo arquivo."""
    agora = time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime()) + ".000Z"
    base = {"schema": "nekkora-rpa/events", "version": "1.2.0", "timestamp_utc": agora, "execution_id": fw_id, "framework_version": "0.3.0"}
    linhas = []
    for s in SENTINELAS:
        linhas.append(dict(base, event_code="ACTION_FAILED", phase="PROCESS", step_id="profile.read_birth_date", action="read_items", attempt=1,
                           error_code="ACTION_TIMEOUT", detail=s, message=s, value=s, cause_type=s, key=s, url=s, payload={"cpf": s}))
        linhas.append(dict(base, event_code="ACTION_COMPLETED", phase="PROCESS", step_id=s, action=s, outcome=s, error_code=s))
    with events.open("a", encoding="utf-8", newline="\n") as f:
        for d in linhas:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
        f.write('{"schema":"nekkora-rpa/events","detail":"' + SENTINELAS[3] + '"' + "\n")  # corrompida
        f.write(json.dumps(dict(base, event_code="ACTION_STARTED", aninhado=[[[[[[SENTINELAS[0]]]]]]])) + "\n")
        f.write(json.dumps(dict(base, event_code="ACTION_STARTED", detail=SENTINELAS[7] * 2000)) + "\n")  # enorme
        f.flush()


if __name__ == "__main__":
    sys.exit(main())

"""STUDIO-AGENT-LOCAL-001 (A02): conferidor independente de uma execução cujo Agent caiu e reabriu. Lê só arquivos (não
importa o Agent): o estado.json e o eventos.jsonl do Agent, o summary.json e o events.jsonl do Framework.

Uso: python conferir_recuperacao.py <pasta da execução no Agent> <pasta de saída da execução> <saida.json>
"""

import json
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
run, out, dest = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
checks = []


def check(what, ok, info=None):
    checks.append({"check": what, "ok": bool(ok), **({"info": info} if info is not None else {})})
    print(("OK    " if ok else "FALHA ") + what + (f"  [{info}]" if info is not None else ""))


estado = json.loads((run / "estado.json").read_text(encoding="utf-8-sig"))
agent_events = [json.loads(x) for x in (run / "eventos.jsonl").read_text(encoding="utf-8-sig").splitlines() if x.strip()]
summary = json.loads((out / "execucao" / "summary.json").read_text(encoding="utf-8"))
fev = [json.loads(x) for x in (out / "execucao" / "events.jsonl").read_text(encoding="utf-8").splitlines() if x.strip()]

# o que o Framework publicou (independente do Agent)
final = summary["status"] in {"COMPLETED", "COMPLETED_WITH_FAILURES", "STOPPED", "INIT_FAILED", "CANCELLED"}
check("summary do Framework presente e com estado final", final, summary["status"])
last = fev[-1]
check("diário do Framework termina em ENGINE_COMPLETED da mesma execução e com o mesmo desfecho",
      last["event_code"] == "ENGINE_COMPLETED" and last["execution_id"] == summary["execution_id"] and last["outcome"] == summary["status"],
      f"{last['event_code']} {last.get('outcome')}")
success = summary["status"] == "COMPLETED" and (summary.get("output") or {}).get("status") == "COMMITTED"
expected = "SUCCEEDED" if success else "FAILED"

# o que o Agent registrou
check(f"estado do Agent = o que o protocolo determina ({expected}), não OUTCOME_UNKNOWN", estado["state"] == expected, estado["state"])
check("código = AGT-EXE-022 (falha informada) ou nulo (sucesso)", estado["code"] == (None if success else "AGT-EXE-022"), estado["code"])
check("marcado como recuperado do protocolo depois do reinício", estado.get("recovered") is True)
check("sem código de saída (o fim do processo não foi observado)", estado["exitCode"] is None)
check("contagens do registro = contagens do summary", estado["counts"] == summary["counts"], estado["counts"])
check("sem progresso parcial (há resultado final)", "progress" not in estado)
check("não adotado (o processo já tinha terminado na reabertura)", estado["adopted"] is False)
names = [e["event"] for e in agent_events]
check("ciclo do Agent: STARTING → RUNNING → RECONCILE → desfecho (nenhum segundo lançamento)", names == ["STARTING", "RUNNING", "RECONCILE", expected], names)
check("nada relançado: um único ENGINE_STARTED no diário do Framework", sum(1 for e in fev if e["event_code"] == "ENGINE_STARTED") == 1)
check("o processo terminou ANTES da reabertura (ENGINE_COMPLETED anterior ao RECONCILE)",
      last["timestamp_utc"] < next(e["at"] for e in agent_events if e["event"] == "RECONCILE"))

dest.write_text(json.dumps({"schema": "nekkora-agent-local-001/conferencia-recuperacao", "version": "1.0.0", "agent_execution_id": estado["id"],
                            "framework_execution_id": summary["execution_id"], "checks": checks}, ensure_ascii=False, indent=2), encoding="utf-8")
fails = sum(1 for c in checks if not c["ok"])
print(f"RESULTADO: {len(checks)} verificações, {fails} falha(s)")
sys.exit(0 if fails == 0 else 2)

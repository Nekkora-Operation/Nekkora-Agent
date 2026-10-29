"""STUDIO-AGENT-LOCAL-001 (cópia do conferidor da COMPAT-001, registro 1.1.0 e schema REAL do repositório): conferidor independente de uma execução do pacote do Studio pelo Agent.

Uso: python conferir.py <pacote original> <dados do Agent> <raiz de saída> <planilha do teste autorizado> <schema OBS> <saida.json>
Lê só arquivos; não importa nada do Agent nem do Studio. Imprime OK/FALHA e grava o resumo.
"""
import hashlib
import json
import re
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
pkg, data, outroot, studio_xlsx, schema_path, dest = (Path(a) for a in sys.argv[1:7])
checks = []


def check(what, ok, info=None):
    checks.append({"check": what, "ok": bool(ok), **({"info": info} if info is not None else {})})
    print(("OK    " if ok else "FALHA ") + what + (f"  [{info}]" if info is not None else ""))


def h(data_bytes):
    try:
        t = data_bytes.decode("utf-8")
        data_bytes = t.replace("\r\n", "\n").encode("utf-8")
    except UnicodeDecodeError:
        pass
    return "sha256:" + hashlib.sha256(data_bytes).hexdigest()


manifest = json.loads((pkg / "manifest.json").read_text(encoding="utf-8"))
files = manifest["files"]
# 1. integridade do pacote original contra o manifesto
bad = [k for k, v in files.items() if h((pkg / k).read_bytes()) != v]
check(f"pacote original: {len(files)} arquivos conferem com o manifesto (SHA-256 sobre LF)", not bad, bad or None)

# 2. impressão digital recalculada (algoritmo do Agent, lido do código) e comparada com o registro
b = "nekkora-agent/package-fingerprint/1\nSTUDIO_PYTHON\n" + "manifest.json\t" + h((pkg / "manifest.json").read_bytes()) + "\n"
for k in sorted(files):
    b += f"{k}\t{files[k]}\n"
fp = "sha256:" + hashlib.sha256(b.encode("utf-8")).hexdigest()
runs = sorted((data / "execucoes").iterdir())
check("uma execução registrada pelo Agent", len(runs) == 1, len(runs))
run = runs[0]
estado = json.loads((run / "estado.json").read_text(encoding="utf-8-sig"))
check("impressão digital recalculada = a do registro do Agent", fp == estado["fingerprint"], fp[:23])

# 3. cópia conferida: o processo rodou da cópia, idêntica ao original
copy = run / "pacote"
diff = [k for k in list(files) + ["manifest.json"] if (copy / k).read_bytes() != (pkg / k).read_bytes()]
check("cópia conferida do Agent idêntica byte a byte ao pacote do Studio", copy.is_dir() and not diff, diff or None)

# 4. registro do supervisor
check("estado.json nekkora-agent/execution 1.1.0 SUCCEEDED, código nulo, exit 0, protocolo",
      estado["schema"] == "nekkora-agent/execution" and estado["version"] == "1.1.0" and estado["state"] == "SUCCEEDED"
      and estado["code"] is None and estado["exitCode"] == 0)
check("1.1.0: origem do pedido LOCAL_UI; não recuperado; sem cancelamento nem progresso parcial (houve resultado final)",
      estado.get("origin") == {"kind": "LOCAL_UI"} and estado.get("recovered") is False and "cancellation" not in estado and "progress" not in estado)
check("execução não adotada, sem suspensão nem bloqueio", not estado["adopted"] and estado["suspensions"] == 0 and estado["locks"] == 0)

# 5. protocolo do Framework
out = outroot / estado["id"]
summary = json.loads((out / "execucao" / "summary.json").read_text(encoding="utf-8"))
check("summary nekkora-rpa/run-summary 1.2.0, Framework 0.3.0 = manifesto", summary["version"] == "1.2.0"
      and summary["framework_version"] == manifest["framework"].split("==")[1])
check("summary COMPLETED, planilha COMMITTED, 5 transações SUCCEEDED", summary["status"] == "COMPLETED"
      and summary["output"]["status"] == "COMMITTED" and summary["counts"] == {"SUCCEEDED": 5} and summary["output"]["rows"] == 5)
check("contagens do registro = contagens do summary", estado["counts"] == summary["counts"])
fev = [json.loads(x) for x in (out / "execucao" / "events.jsonl").read_text(encoding="utf-8").splitlines() if x.strip()]
check("events.jsonl do Framework: schema nekkora-rpa/events 1.2.0 em todas as linhas",
      all(e.get("schema") == "nekkora-rpa/events" and e.get("version") == "1.2.0" for e in fev), len(fev))
check("execution_id do Framework único e igual ao do summary", {e["execution_id"] for e in fev} == {summary["execution_id"]})

# 6. observabilidade 1.0.0
act = [json.loads(x) for x in (run / "atividade.jsonl").read_text(encoding="utf-8").splitlines() if x.strip()]
schema = json.loads(schema_path.read_text(encoding="utf-8"))
try:
    import jsonschema
    v = jsonschema.Draft202012Validator(schema)
    invalid = [i for i, e in enumerate(act) if list(v.iter_errors(e))]
    check(f"atividade.jsonl: {len(act)} envelopes válidos contra o schema nekkora-agent/observability 1.0.0", not invalid, invalid[:5] or None)
except ImportError:
    check("jsonschema disponível para validar o envelope", False)
seq = [e["sequence"] for e in act]
check("sequence contínua 1..N", seq == list(range(1, len(act) + 1)))
check("um único agent_execution_id = o do registro", {e["agent_execution_id"] for e in act} == {estado["id"]})
fw_ids = {e.get("correlation", {}).get("framework_execution_id") for e in act if e["source"] == "FRAMEWORK"}
check("correlação secundária = execution_id do Framework", fw_ids == {summary["execution_id"]}, sorted(fw_ids))
n_fw = sum(1 for e in act if e["source"] == "FRAMEWORK")
check("todos os eventos do Framework entraram no envelope", n_fw == len(fev), f"{n_fw}/{len(fev)}")
done = [e for e in act if e["event_code"] == "COLLECTION_COMPLETED"]
check("COLLECTION_COMPLETED sem descarte, inválido, código ou campo desconhecido",
      len(done) == 1 and all(done[0]["counts"].get(k, 0) == 0 for k in ("dropped", "invalid", "unknown_fields", "unknown_codes")),
      done[0]["counts"] if done else None)
gaps = [e["event_code"] for e in act if e["event_code"] in ("EVENT_GAP", "EVENT_INVALID", "SOURCE_REPLACED", "SOURCE_UNSAFE", "HISTORY_PURGED")]
check("nenhuma lacuna ou recusa declarada", not gaps, gaps or None)
steps = {s["traceId"]: s["stepId"] for s in manifest["steps"]}
mapped = {e["step_id"]: e.get("studio_step_id") for e in act if e.get("step_id") in steps}
check("studio_step_id = stepId do manifesto para cada traceId emitido",
      bool(mapped) and all(mapped[t] == steps[t] for t in mapped), f"{len(mapped)} de {len(steps)} passos")
agent_codes = [e["event_code"] for e in act if e["source"] == "AGENT"]
check("ciclo do supervisor no envelope: STARTING → RUNNING → SUCCEEDED", agent_codes == ["STARTING", "RUNNING", "SUCCEEDED"], agent_codes)

# 7. privacidade: nenhum valor de negócio nos registros do Agent
raw = (run / "atividade.jsonl").read_text(encoding="utf-8") + (run / "eventos.jsonl").read_text(encoding="utf-8") + (run / "estado.json").read_text(encoding="utf-8-sig")
leaks = [w for w in ("Machado", "Clarice", "Curie", "Einstein", "Montenegro", "wikipedia", "Users\\", "<usuario>") if w.lower() in raw.lower()]
check("nenhum termo da lista, endereço, usuário ou caminho nos registros do Agent", not leaks, leaks or None)

# 8. resultado de negócio igual ao do teste autorizado do Studio (mesma entrada)
try:
    import openpyxl

    def rows(p):
        ws = openpyxl.load_workbook(p, read_only=True).active
        return [tuple(c for c in r) for r in ws.iter_rows(values_only=True)]
    a, s = rows(out / "planilha.xlsx"), rows(studio_xlsx)
    check("planilha do Agent = planilha do teste autorizado do Studio (cabeçalho + 5 linhas, por tipo)", a == s, f"{len(a)} linhas")
except ImportError:
    check("openpyxl disponível", False)

Path(dest).write_text(json.dumps({"schema": "nekkora-compat-001/conferencia", "version": "1.0.0", "agent_execution_id": estado["id"],
                                  "framework_execution_id": summary["execution_id"], "fingerprint": fp, "manifest_sha256":
                                  hashlib.sha256((pkg / "manifest.json").read_bytes()).hexdigest(), "envelopes": len(act),
                                  "framework_events": len(fev), "checks": checks}, ensure_ascii=False, indent=2), encoding="utf-8")
fails = sum(1 for c in checks if not c["ok"])
print(f"RESULTADO: {len(checks)} verificações, {fails} falha(s)")
sys.exit(0 if fails == 0 else 2)

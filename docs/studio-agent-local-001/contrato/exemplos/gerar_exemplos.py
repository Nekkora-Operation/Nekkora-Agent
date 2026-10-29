"""Gera docs/studio-agent-local-001/contrato/exemplos/{validos.jsonl,invalidos.json} (valores fixos e fictícios)."""

import copy
import json
import sys
from pathlib import Path

dest = Path(sys.argv[1])
dest.mkdir(parents=True, exist_ok=True)


def u(n: int) -> str:
    return f"{n:08x}-0000-4000-8000-{n:012x}"


B = "A" * 42 + "w"  # 32 bytes em base64url sem preenchimento (fictício)
EX = "ex-" + "6f670400dd5d9a00d9569fdec8d2c4f4"
FP = "sha256:" + "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d"
M = "ca9439c74461463ca49c3b6b862b2c6452034d398c30e1fec43264482dde32b5"
seq = iter(range(1, 1000))


def msg(t: str, payload: dict, mid: int, reply: int | None = None, with_seq: bool = True) -> dict:
    m = {"schema": "nekkora-agent/local-control", "version": "1.0.0", "type": t, "message_id": u(mid)}
    if with_seq:
        m["seq"] = next(seq)
    if reply is not None:
        m["reply_to"] = u(reply)
    m["payload"] = payload
    return m


request = {
    "request_id": u(500),
    "package": {"path": "C:\\Automacoes\\biografias", "expected_fingerprint": FP, "manifest_sha256": M},
    "runtime": {"python": "C:\\Ambientes\\0.3.0-excel\\venv\\Scripts\\python.exe"},
    "input": "C:\\Entradas\\artigos.csv",
    "output_root": "C:\\Saidas",
    "timeout_seconds": 3600,
    "correlation": {"project_ref": "wikipedia-biografias", "automation_ref": "biografias", "code_revision_ref": "r-14"},
}
snapshot_done = {"agent_execution_id": EX, "state": "SUCCEEDED", "created": "2026-10-28T12:35:05.896Z", "started": "2026-10-28T12:35:06.001Z",
                 "ended": "2026-10-28T12:35:14.300Z", "exit_code": 0, "adopted": False, "recovered": False, "record_saved": True,
                 "counts": {"SUCCEEDED": 5}, "origin": {"kind": "STUDIO_LOCAL", "pairing_id": u(900), "request_id": u(500)}}
snapshot_cancel = {"agent_execution_id": EX, "state": "CANCELLED", "code": "AGT-EXE-024", "created": "2026-10-28T12:41:46.033Z",
                   "started": "2026-10-28T12:41:46.993Z", "ended": "2026-10-28T12:41:58.280Z", "exit_code": 1, "adopted": False, "recovered": False,
                   "record_saved": True, "counts": {}, "progress": {"available": True, "transactions": {"IN_PROGRESS": 1, "SUCCEEDED": 7}, "delivered": 7,
                                                                    "problem": None},
                   "cancellation": {"requested": "2026-10-28T12:41:57.476Z", "source": "STUDIO_LOCAL", "outcome": "CONFIRMED"},
                   "origin": {"kind": "STUDIO_LOCAL", "pairing_id": u(900), "request_id": u(500)}}
envelope = {"schema": "nekkora-agent/observability", "version": "1.0.0", "agent_execution_id": EX, "sequence": 1,
            "observed_utc": "2026-10-28T12:35:06.006Z", "source": "COLLECTOR", "event_code": "COLLECTION_STARTED", "severity": "INFO",
            "category": "TELEMETRY", "counts": {"framework_source": 1}}

valid = [
    msg("auth.challenge", {"instance_id": B, "server_nonce": B, "accepts": ["1.0.0"]}, 1, with_seq=False),
    msg("auth.proof", {"client_nonce": B, "proof_secret": B, "pairing_id": u(900), "proof_pairing": B, "studio_version": "1.0.0-SNAPSHOT"}, 2,
        with_seq=False),
    msg("auth.ok", {"server_proof": B, "contract_version": "1.0.0", "heartbeat_ms": 15000, "paired": True,
                    "limits": {"max_frame_bytes": 65536, "max_messages_per_second": 20, "max_requests_per_hour": 10, "max_activity_page": 500}}, 3,
        reply=2, with_seq=False),
    msg("pair.begin", {}, 4),
    msg("pair.pending", {"sas": "048213", "expires_in_s": 120}, 5, reply=4),
    msg("pair.result", {"outcome": "PAIRED", "pairing_id": u(900), "pairing_key": B}, 6, reply=4),
    msg("pair.result", {"outcome": "REFUSED"}, 7, reply=4),
    msg("run.request", request, 8),
    msg("run.received", {"request_id": u(500), "request_state": "AWAITING_CONFIRMATION", "agent_fingerprint": FP}, 9, reply=8),
    msg("run.refused", {"request_id": u(501), "code": "AGT-EXE-006"}, 10, reply=8),
    msg("run.refused", {"request_id": u(502), "code": "AGT-LCL-008"}, 11, reply=8),
    msg("request.status", {"request_id": u(500)}, 12),
    msg("request.snapshot", {"request_id": u(500), "request_state": "STARTED", "agent_execution_id": EX, "agent_fingerprint": FP}, 13, reply=12),
    msg("request.snapshot", {"request_id": u(503), "request_state": "UNKNOWN_REQUEST"}, 14, reply=12),
    msg("request.withdraw", {"request_id": u(500)}, 15),
    msg("run.status", {"agent_execution_id": EX}, 16),
    msg("run.snapshot", snapshot_done, 17, reply=16),
    msg("run.snapshot", snapshot_cancel, 18, reply=16),
    msg("run.activity", {"agent_execution_id": EX, "after_sequence": 0, "max": 500}, 19),
    msg("run.activity.page", {"agent_execution_id": EX, "events": [envelope], "last_sequence": 1, "more": True}, 20, reply=19),
    msg("run.cancel", {"agent_execution_id": EX, "cancel_id": u(700)}, 21),
    msg("run.cancel.result", {"cancel_id": u(700), "result": "REQUESTED"}, 22, reply=21),
    msg("run.changed", {"agent_execution_id": EX, "state": "CANCELLED", "last_sequence": 420}, 23),
    msg("run.changed", {"request_id": u(500), "state": "AWAITING_APPROVAL"}, 24),
    msg("heartbeat", {}, 25),
    msg("error", {"code": "AGT-LCL-006", "fatal": False}, 26),
]


def bad(reason: str, base: dict, mutate) -> dict:
    m = copy.deepcopy(base)
    mutate(m)
    return {"motivo": reason, "mensagem": m}


req = valid[7]
snap = valid[16]
invalid = [
    bad("versão de contrato desconhecida", req, lambda m: m.update(version="2.0.0")),
    bad("tipo desconhecido (aprovação pelo canal não existe)", req, lambda m: m.update(type="package.approve")),
    bad("campo livre no envelope", req, lambda m: m.update(note="executar já")),
    bad("sem seq depois do handshake", req, lambda m: m.pop("seq")),
    bad("pedido sem request_id", req, lambda m: m["payload"].pop("request_id")),
    bad("pedido com campo de aprovação (pedido não aprova pacote)", req, lambda m: m["payload"].update(approve=True)),
    bad("pedido com execução automática", req, lambda m: m["payload"].update(auto_start=True)),
    bad("caminho do pacote relativo", req, lambda m: m["payload"]["package"].update(path="automacoes\\biografias")),
    bad("caminho do pacote em rede (UNC)", req, lambda m: m["payload"]["package"].update(path="\\\\servidor\\share\\pacote")),
    bad("caminho com prefixo de dispositivo", req, lambda m: m["payload"]["package"].update(path="\\\\?\\C:\\Automacoes\\pacote")),
    bad("caminho com '..'", req, lambda m: m["payload"]["package"].update(path="C:\\Automacoes\\..\\Windows")),
    bad("impressão digital fora do formato", req, lambda m: m["payload"]["package"].update(expected_fingerprint="5664f1a5")),
    bad("prazo abaixo do mínimo", req, lambda m: m["payload"].update(timeout_seconds=5)),
    bad("correlação com texto livre", req, lambda m: m["payload"]["correlation"].update(project_ref="Cliente Sigiloso S/A")),
    bad("argumentos de linha de comando livres", req, lambda m: m["payload"].update(arguments=["--channel", "msedge"])),
    bad("prova com tamanho errado", valid[1], lambda m: m["payload"].update(proof_secret="abc")),
    bad("pairing_id sem proof_pairing", valid[1], lambda m: m["payload"].pop("proof_pairing")),
    bad("pareamento recusado não traz chave", valid[6], lambda m: m["payload"].update(pairing_key=B)),
    bad("pareamento concluído sem chave", valid[5], lambda m: m["payload"].pop("pairing_key")),
    bad("código fora do catálogo", valid[9], lambda m: m["payload"].update(code="AGT-EXE-099")),
    bad("snapshot STARTED sem agent_execution_id", valid[12], lambda m: m["payload"].pop("agent_execution_id")),
    bad("estado de execução novo (o canal não muda o ciclo de vida)", snap, lambda m: m["payload"].update(state="AWAITING_APPROVAL")),
    bad("SUCCEEDED com progresso parcial (parcial não é conclusão)", snap,
        lambda m: m["payload"].update(progress={"available": True, "transactions": {"SUCCEEDED": 5}, "delivered": 5, "problem": None})),
    bad("origem STUDIO_LOCAL sem pareamento", snap, lambda m: m["payload"]["origin"].pop("pairing_id")),
    bad("origem LOCAL_UI com pareamento", snap, lambda m: m["payload"]["origin"].update(kind="LOCAL_UI")),
    bad("em andamento com fim", snap, lambda m: m["payload"].update(state="RUNNING")),
    bad("página de atividade acima de 500", valid[18], lambda m: m["payload"].update(max=501)),
    bad("cancelamento sem cancel_id", valid[20], lambda m: m["payload"].pop("cancel_id")),
    bad("resultado de cancelamento fora do enum", valid[21], lambda m: m["payload"].update(result="CANCELLED")),
    bad("aviso com os dois identificadores", valid[22], lambda m: m["payload"].update(request_id=u(500))),
    bad("batimento com conteúdo", valid[24], lambda m: m["payload"].update(x=1)),
    bad("erro com código de outra família", valid[25], lambda m: m["payload"].update(code="AGT-EXE-004")),
    bad("limite de quadro diferente de 64 KiB", valid[2], lambda m: m["payload"]["limits"].update(max_frame_bytes=12582912)),
]

(dest / "validos.jsonl").write_text("".join(json.dumps(m, ensure_ascii=False) + "\n" for m in valid), encoding="utf-8")
(dest / "invalidos.json").write_text(json.dumps(invalid, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(len(valid), "válidos,", len(invalid), "inválidos")

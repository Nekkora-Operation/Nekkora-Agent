"""STUDIO-AGENT-LOCAL-001: confere o progresso parcial do estado.json do Agent contra o checkpoint carregado pelo PRÓPRIO
Framework (CheckpointStore.load confere a integridade). Uso: python conferir_progresso.py <estado.json> <pasta de saída da execução> <process_name>"""
import json
import sys
from collections import Counter
from pathlib import Path

from nekkora_rpa.core.checkpoints import CheckpointStore

sys.stdout.reconfigure(encoding="utf-8")
estado = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
store = CheckpointStore(Path(sys.argv[2]) / "execucao" / "checkpoint.json", sys.argv[3])
store.load()  # lança CheckpointCorrupted se a integridade não conferir
by_status = dict(Counter(r.status.value for r in store.records.values()))
delivered = sum(1 for r in store.records.values() if r.delivery.value == "COMMITTED")
p = estado["progress"]
ok = p["available"] and p["transactions"] == by_status and p["delivered"] == delivered
summary = (Path(sys.argv[2]) / "execucao" / "summary.json").exists()
print(f"Framework (load com integridade): {by_status}, entregues {delivered}; Agent: {p['transactions']}, entregues {p['delivered']}")
print(f"{'OK   ' if ok else 'FALHA'} progresso parcial do Agent = checkpoint carregado pelo Framework")
print(f"{'OK   ' if not summary else 'FALHA'} nenhum resultado final publicado (summary.json ausente)")
print(f"{'OK   ' if estado['counts'] == {} else 'FALHA'} contagens finais do registro vazias (parcial não vira resultado)")
sys.exit(0 if ok and not summary and estado["counts"] == {} else 2)

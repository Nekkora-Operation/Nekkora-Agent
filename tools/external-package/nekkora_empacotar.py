#!/usr/bin/env python3
"""Gera o pacote Python EXTERNO executavel pelo Nekkora Agent (AGENT-PYTHON-001, Marco 2).

"Build" aqui e empacotar uma versao identificada do projeto para o Agent executar. NAO converte Python em EXE, NAO
instala nada e NAO executa o projeto. So biblioteca padrao: nenhuma dependencia Nekkora.

Uso (Windows):
    py -3 nekkora_empacotar.py C:\\projetos\\relatorio --nome relatorio-csv --versao 1.0.0 --entrada main.py --saida C:\\pacotes

O que entra:
  * os arquivos do projeto (texto e binario), com o caminho relativo a pasta;
  * o descritor nekkora-external.json (schema nekkora-agent/external-python-package 1.0.0): nome, versao do projeto,
    entrada relativa, versao de Python, dependencias com versao EXATA (lidas do requirements.txt) e o hash de cada arquivo.
O que NUNCA entra (recusado mesmo com --incluir): .venv/venv/env, __pycache__, .git/.hg/.svn, .idea/.vscode, node_modules,
  .env*, *.pem, *.key, *.pfx, *.p12, id_rsa*, id_ed25519*, .netrc, .pypirc, *.pyc.
O que fica fora por padrao (dados): dados/, data/, dist/, build/, *.egg-info, *.csv, *.xlsx, *.xls, *.db, *.sqlite,
  *.parquet, *.log. Use --incluir <caminho-relativo> para levar um arquivo de exemplo SEM dados reais.

Identidade (as duas medidas nunca sao comparadas entre si):
  * sha256 do ZIP: os bytes do artefato (o ZIP e deterministico: mesma entrada, mesmos bytes);
  * impressao nekkora-agent/external-python-fingerprint/1: o conteudo (descritor + arquivos listados), a mesma que o Agent e
    a Platform calculam.

Saida: <saida>/<nome>-<versao>.zip e <saida>/<nome>-<versao>.nekkora-external.json; imprime as duas medidas.
Codigos de saida: 0 gerado; 2 uso invalido; 3 pacote recusado (o motivo vai para a saida de erro).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath

SCHEMA = "nekkora-agent/external-python-package"
DESCRIPTOR = "nekkora-external.json"
RULE = "nekkora-agent/external-python-fingerprint/1"
GENERATOR = "nekkora-empacotar/1.0.0"
FIXED_DATE = (1980, 1, 1, 0, 0, 0)

NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
SEMVER = re.compile(r"[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,6}")
PYTHON = re.compile(r"(>=|==|~=)[0-9]{1,2}\.[0-9]{1,2}(\.\*)?(,<[0-9]{1,2}\.[0-9]{1,2})?")
DEPENDENCY = re.compile(r"([A-Za-z0-9][A-Za-z0-9._-]{0,79})(\[[A-Za-z0-9,._-]{1,80}])?==([0-9][0-9A-Za-z.+!-]{0,40})")
SAFE = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}")

FORBIDDEN_SEGMENTS = {".venv", "venv", "env", "__pycache__", ".git", ".hg", ".svn", ".idea", ".vscode", "node_modules"}
DATA_SEGMENTS = {"dados", "data", "dist", "build"}
DATA_SUFFIXES = (".csv", ".xlsx", ".xls", ".db", ".sqlite", ".parquet", ".log")


class Recusa(Exception):
    pass


def forbidden(rel: str) -> bool:
    for seg in rel.split("/"):
        low = seg.lower()
        if (low in FORBIDDEN_SEGMENTS or low.startswith(".env") or low.endswith((".pem", ".key", ".pfx", ".p12", ".pyc"))
                or low.startswith(("id_rsa", "id_ed25519")) or low in (".netrc", ".pypirc")):
            return True
    return False


def data_by_default(rel: str) -> bool:
    segs = rel.lower().split("/")
    return any(s in DATA_SEGMENTS or s.endswith(".egg-info") for s in segs[:-1]) or segs[-1].endswith(DATA_SUFFIXES)


def file_hash(data: bytes) -> str:
    """Como o gerador do Studio, o Agent e a Platform: UTF-8 valido = texto com CRLF trocado por LF; senao, os bytes."""
    try:
        data = data.decode("utf-8").replace("\r\n", "\n").encode("utf-8")
    except UnicodeDecodeError:
        pass
    return "sha256:" + hashlib.sha256(data).hexdigest()


def fingerprint(descriptor: bytes, files: dict[str, str]) -> str:
    lines = [RULE, "EXTERNAL_PYTHON", f"{DESCRIPTOR}\t{file_hash(descriptor)}"]
    lines += [f"{rel}\t{files[rel]}" for rel in sorted(files)]  # ordem de String Java = bytes, para caminhos ASCII
    return hashlib.sha256(("\n".join(lines) + "\n").encode("utf-8")).hexdigest()


def requirements(project: Path) -> list[str]:
    req = project / "requirements.txt"
    if not req.is_file():
        return []
    out, seen = [], set()
    for raw in req.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        m = DEPENDENCY.fullmatch(line)
        if not m:
            raise Recusa(f"dependencia sem versao exata no requirements.txt: {line!r} (use nome==x.y.z)")
        key = m.group(1).lower().replace("_", "-").replace(".", "-")
        if key in seen:
            raise Recusa(f"dependencia repetida: {m.group(1)}")
        seen.add(key)
        out.append(line)
    return out


def collect(project: Path, include: list[str]) -> tuple[dict[str, bytes], list[str]]:
    chosen: dict[str, bytes] = {}
    skipped: list[str] = []
    wanted = {i.replace("\\", "/") for i in include}
    for root, dirs, names in os.walk(project, followlinks=False):
        dirs.sort()
        for n in sorted(names):
            p = Path(root) / n
            rel = PurePosixPath(p.relative_to(project).as_posix()).as_posix()
            if rel == DESCRIPTOR:
                continue
            if p.is_symlink():
                raise Recusa(f"ligacao simbolica nao entra no pacote: {rel}")
            if forbidden(rel):
                if rel in wanted:
                    raise Recusa(f"arquivo proibido no pacote: {rel}")
                skipped.append(rel)
                continue
            if data_by_default(rel) and rel not in wanted:
                skipped.append(rel)
                continue
            if not SAFE.fullmatch(rel) or ".." in rel or "//" in rel:
                raise Recusa(f"caminho inseguro: {rel}")
            chosen[rel] = p.read_bytes()
    missing = wanted - set(chosen)
    if missing:
        raise Recusa(f"--incluir nao encontrado: {sorted(missing)}")
    return chosen, skipped


def build(project: Path, name: str, version: str, entry: str, python: str, out: Path, include: list[str]) -> dict:
    if not project.is_dir():
        raise Recusa("a pasta do projeto nao existe")
    if not NAME.fullmatch(name) or not SEMVER.fullmatch(version):
        raise Recusa("nome ou versao invalidos (versao: x.y.z)")
    if not PYTHON.fullmatch(python):
        raise Recusa("versao de Python invalida (ex.: >=3.10,<3.14)")
    entry = entry.replace("\\", "/")
    if not entry.endswith(".py") or not SAFE.fullmatch(entry) or ".." in entry or entry.startswith("/"):
        raise Recusa("a entrada precisa ser um .py relativo, dentro da pasta do projeto")
    files, skipped = collect(project, include)
    if entry not in files:
        raise Recusa(f"a entrada {entry} nao esta entre os arquivos do pacote")
    if len(files) > 500:
        raise Recusa("mais de 500 arquivos")
    hashes = {rel: file_hash(data) for rel, data in files.items()}
    descriptor = {
        "schema": SCHEMA,
        "version": "1.0.0",
        "name": name,
        "packageVersion": version,
        "entry": entry,
        "python": python,
        "dependencies": requirements(project),
        "files": dict(sorted(hashes.items())),
        "generator": GENERATOR,
    }
    desc_bytes = (json.dumps(descriptor, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    out.mkdir(parents=True, exist_ok=True)
    zpath = out / f"{name}-{version}.zip"
    tmp = zpath.with_suffix(".zip.tmp")
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel, data in [(DESCRIPTOR, desc_bytes)] + sorted(files.items()):
            info = zipfile.ZipInfo(rel, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            info.create_system = 0
            z.writestr(info, data)
    os.replace(tmp, zpath)
    (out / f"{name}-{version}.{DESCRIPTOR}").write_bytes(desc_bytes)
    return {
        "zip": str(zpath),
        "sha256": hashlib.sha256(zpath.read_bytes()).hexdigest(),
        "fingerprintRule": RULE,
        "fingerprint": fingerprint(desc_bytes, hashes),
        "files": len(files),
        "skipped": skipped,
        "dependencies": descriptor["dependencies"],
    }


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="Gera o pacote Python externo para o Nekkora Agent (nao executa o projeto).")
    ap.add_argument("projeto", type=Path)
    ap.add_argument("--nome", required=True)
    ap.add_argument("--versao", required=True)
    ap.add_argument("--entrada", required=True)
    ap.add_argument("--python", default=">=3.10,<3.14")
    ap.add_argument("--saida", type=Path, default=Path("dist-nekkora"))
    ap.add_argument("--incluir", action="append", default=[])
    try:
        a = ap.parse_args(argv)
    except SystemExit as e:
        return 2 if e.code else 0
    try:
        r = build(a.projeto.resolve(), a.nome, a.versao, a.entrada, a.python, a.saida.resolve(), a.incluir)
    except Recusa as e:
        print(f"pacote recusado: {e}", file=sys.stderr)
        return 3
    print(f"pacote:     {r['zip']}")
    print(f"arquivos:   {r['files']} (fora do pacote: {len(r['skipped'])})")
    for s in r["skipped"]:
        print(f"  fora: {s}")
    print(f"dependencias: {', '.join(r['dependencies']) or '(nenhuma)'}")
    print(f"sha256 do ZIP (bytes do artefato): {r['sha256']}")
    print(f"impressao ({r['fingerprintRule']}): {r['fingerprint']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

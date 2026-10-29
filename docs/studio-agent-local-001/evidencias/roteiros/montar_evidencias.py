"""Copia as evidências SELECIONADAS da sessão para docs/studio-agent-local-001/evidencias, trocando caminhos pessoais,
usuário e nome da máquina por marcadores. Imagens são copiadas como estão (a tela do Agent só mostra nomes curtos;
conferidas uma a uma antes). Falha se sobrar qualquer marca pessoal num arquivo de texto."""

import os
import re
import shutil
import sys
from pathlib import Path

src, dst = Path(sys.argv[1]), Path(sys.argv[2])
# usuário e máquina vêm do ambiente: este arquivo não carrega nenhum nome pessoal
NAME = re.escape(os.environ["USERNAME"])
HOST = re.escape(os.environ["COMPUTERNAME"])
SEP = r"(?:\\\\|\\|/)"
USER = r"(?:[Cc]:" + SEP + r"Users" + SEP + NAME + r"|/c/Users/" + NAME + r")"
RULES = [
    (re.compile(USER + SEP + r"AppData" + SEP + r"Local" + SEP + r"Temp" + SEP + r"claude" + SEP + r"[^\\/\"\s]+" + SEP + r"[0-9a-f-]{36}" + SEP
                + r"scratchpad"), "<sessao>"),
    (re.compile(USER + SEP + r"AppData" + SEP + r"Local" + SEP + r"Temp"), "<temp>"),
    (re.compile(USER + SEP + r"\.nekkora-compat-g1"), "<dados-studio-compat>"),
    (re.compile(USER + SEP + r"nekkora"), "<nekkora>"),
    (re.compile(USER), "<usuario>"),
    (re.compile(r"C--Users-" + NAME + r"-nekkora"), "<projeto>"),
    (re.compile(HOST, re.I), "<maquina>"),
    (re.compile(r"\b" + NAME + r"\b"), "<usuario>"),
]
TEXT = {".txt", ".json", ".jsonl", ".md", ".csv", ".log", ".ps1", ".py", ".sh", ".java"}
# caminho de perfil de verdade (o conferidor tem "Users\\" como TERMO de busca de vazamento, e isso é legítimo)
BAD = re.compile(NAME + r"|" + HOST + r"|[A-Za-z]:(?:\\\\|\\|/)Users|/c/Users/", re.I)


def copy(f: Path, to: Path) -> None:
    to.parent.mkdir(parents=True, exist_ok=True)
    if f.suffix.lower() not in TEXT:
        shutil.copyfile(f, to)
        return
    raw = f.read_bytes()
    bom = raw.startswith(b"\xef\xbb\xbf")
    t = raw.decode("utf-8-sig", errors="replace") if bom else raw.decode("utf-8", errors="replace")
    if "�" in t and f.suffix.lower() in {".txt", ".log"}:
        t = raw.decode("cp1252", errors="replace")  # saída do PowerShell 5.1 em ANSI
    for rx, rep in RULES:
        t = rx.sub(rep, t)
    # o próprio sanitizador tem os PADRÕES de caminho (sem nome pessoal: ele vem do ambiente)
    left = re.search(NAME + r"|" + HOST, t, re.I) if f.name == "montar_evidencias.py" else BAD.search(t)
    if left:
        raise SystemExit(f"marca pessoal em {f}: …{t[max(0, left.start() - 40):left.end() + 40]}…")
    to.write_bytes((b"\xef\xbb\xbf" if bom else b"") + t.encode("utf-8"))


n = 0
real = src / "real"
for case in sorted(p for p in real.iterdir() if p.is_dir() and not p.name.startswith("saida-")):
    out = dst / "real" / case.name
    for f in case.iterdir():
        if f.is_file() and not f.name.endswith("pipes-antes.txt"):
            copy(f, out / f.name)
            n += 1
        elif f.is_dir() and "isolamento" in f.name:
            copy(f / "isolamento.json", out / f.name / "isolamento.json")
            n += 1
    console = real / f"{case.name}-console.txt"
    if console.exists():
        copy(console, out / "console.txt")
        n += 1
for name in ("java", "pipe2"):
    copy(src / "iso-controle" / name / "isolamento.json", dst / "isolamento" / f"controle-{name}" / "isolamento.json")
    n += 1
copy(src / "iso-controle" / "Escuta.java", dst / "isolamento" / "Escuta.java")
for f in ("writer.py", "Reader.java", "resultado.txt"):
    copy(src / "exp" / f, dst / "f-fw-04" / f)
    n += 1
for f in (src / "tela").iterdir():
    copy(f, dst / "tela" / f.name)
    n += 1
for f in ("a01-vermelho.log", "a02-vermelho.log", "a03-vermelho.log"):
    copy(src / f, dst / "vermelho" / f)
    n += 1
for f in ("run-in-agent-sal.ps1", "patch_roteiro.py", "conferir-sal.py", "conferir_progresso.py", "conferir_recuperacao.py", "run.sh",
          "montar_evidencias.py"):
    copy(src / "roteiros" / f, dst / "roteiros" / f)
    n += 1
print(f"{n} arquivos")

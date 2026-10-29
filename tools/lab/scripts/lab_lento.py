"""Laboratório: lança um processo FILHO e os dois esperam muito (para cancelar, esgotar o prazo, reiniciar o Agent ou
suspender o Windows). Grava os PIDs em ``pids.json`` na pasta de saída (só para o teste conferir quem foi encerrado).
"""

import json
import os
import subprocess
import sys
import time

from lab_comum import saida

pasta = saida()
filho = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(900)"])
(pasta / "pids.json").write_text(json.dumps({"pai": os.getpid(), "filho": filho.pid}), encoding="utf-8")
time.sleep(900)

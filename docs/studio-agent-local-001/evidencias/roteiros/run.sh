w () 
{ 
    cygpath -w "$1"
}
run () 
{ 
    local label=$1 input=$2;
    shift 2;
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(w $U/roteiros/run-in-agent-sal.ps1)" -Package "$(w $CU/review-g1/final)" -Python "$(w <dados-studio-compat>/hs/ambientes/2b1b2d03e372/0.3.0-excel/venv/Scripts/python.exe)" -InputFile "$input" -OutputRoot "$(w $U/real/saida-$label)" -Out "$(w $U/real/$label)" -Zip "$(w $U/zip-final/nekkora-agent-0.1.0-windows-x64-lab.zip)" -Label "$label" -AgentModule "$(w <nekkora>/Nekkora-Agent/tools/homologation)" -IsolationScript "$(w <nekkora>/Nekkora-Agent/tools/isolation/verificar-isolamento.ps1)" "$@" > $U/real/$label-console.txt 2>&1;
    echo "== $label rc=$?";
    grep -E "^(OK|FALHA)|RESULTADO|ISO-R|esgotado" $U/real/$label-console.txt | tail -20
}
U=<sessao>/sal001; CU=<sessao>/compat

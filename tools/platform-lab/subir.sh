#!/usr/bin/env bash
# AGENT-PLATFORM-001 — laboratório DESCARTÁVEL com a API REAL para a prova integrada do Agent.
#
# Sobe a stack E2E da própria API (api/e2e/compose.e2e.yml e Dockerfile, SEM alterar a API) com um nome de projeto,
# portas e pasta de estado próprios: nginx (TLS, origem única) → Platform, Portal e canal do Agent (nekkora-agent-gateway)
# → PostgreSQL 16 e Redis. Por que não o runner de ops/homologacao: a stack de homologação não tem o processo do canal nem a
# rota /agent/api/v1 (o contrato, sec. 12.9, aponta api/e2e para a prova integrada).
#
# Migrations: 001..027 da cópia da API, em INSTALAÇÃO LIMPA, pelo modelo do migrador suportado
# (db/bootstrap/aplicar_como_migrador.sh, o mesmo de api/e2e/run.sh e das bases de teste): elevação só nas versões que a
# exigem, retirada e conferência 0|0|0 em cada janela, dentro do initdb do banco novo. Nenhuma linha de elevação de outro
# ambiente é usada. O log do migrador é conferido (sem FALHA, fim 0|0|0, invariantes da 016).
#
# Segredos (senhas dos papéis, chaves) são ALEATÓRIOS por laboratório e ficam só em $SEG (fora dos repositórios, 600).
# A chave privada TLS é apagada assim que o nginx a carrega. Nunca toca outras stacks (nome de projeto, rede e portas
# conferidos antes), nunca usa "down -v" nem limpeza global.
#
# Uso: tools/platform-lab/subir.sh <id> <cópia da API na main>
#      LAB_PORTA=55871 LAB_PG_PORTA=55872 tools/platform-lab/subir.sh agentlab-001 ../npa-main-agentlab
set -euo pipefail

ID="${1:?uso: subir.sh <id> <copia da API>}"
API_REPO="$(cd "${2:?copia da API}" && pwd)"
AQUI="$(cd "$(dirname "$0")" && pwd)"
case "$ID" in agentlab-[0-9a-z-]*) ;; *) echo "ERRO: id deve começar com agentlab-" >&2; exit 1;; esac
export E2E_PROJETO="nekkora-$ID"
export E2E_PORTA="${LAB_PORTA:-55871}"
export E2E_PG_PORTA="${LAB_PG_PORTA:-55872}"
SEG="$HOME/.nekkora/agent-lab/$ID"
SAIDA="$AQUI/saidas/$ID"
COMPOSE=(docker compose -f "$API_REPO/api/e2e/compose.e2e.yml" -f "$AQUI/compose.lab.yml")

falha() { echo "ERRO: $*" >&2; exit 1; }
limpar_chave() { rm -f "$SEG/tls/key.pem"; }
trap 'rc=$?; limpar_chave; exit $rc' EXIT

# --- proteção de recursos existentes ---
[ -d "$SEG" ] && falha "laboratório $ID já existe ($SEG): use outro id"
for p in "$E2E_PORTA" "$E2E_PG_PORTA"; do
    [ "$p" != "5432" ] || falha "porta 5432 é da stack principal"
    if docker ps --format '{{.Ports}}' | grep -q ":$p->"; then falha "porta $p já publicada"; fi
    if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then falha "porta $p em uso"; fi
done
[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$E2E_PROJETO")" ] || falha "projeto $E2E_PROJETO já tem containers"
docker network inspect "${E2E_PROJETO}_e2e" >/dev/null 2>&1 && falha "rede ${E2E_PROJETO}_e2e já existe"
if docker network ls -q | xargs -r docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>/dev/null | grep -q '10.251.47.0/24'; then
    falha "sub-rede 10.251.47.0/24 (da stack E2E) já em uso por outra rede"
fi
for j in nekkora-platform nekkora-portal nekkora-agent-gateway; do
    ls "$API_REPO/api/$j/target/$j-0.1.0-SNAPSHOT.jar" >/dev/null 2>&1 || falha "jar ausente: $j (rode mvnw -DskipTests package na cópia da API)"
done

mkdir -p "$SEG/migracoes_montadas/migracoes" "$SEG/migracoes_montadas/bootstrap" "$SEG/tls" "$SAIDA"
chmod 700 "$SEG" 2>/dev/null || true
COMMIT="$(git -C "$API_REPO" rev-parse HEAD)"
ATE="$(ls "$API_REPO/db/migrations" | sed -n 's/^\([0-9]\{3\}\)_.*\.sql$/\1/p' | sort | tail -1)"
for i in $(seq 1 "$((10#$ATE))"); do
    n="$(printf '%03d' "$i")"
    f=("$API_REPO"/db/migrations/${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration $n ausente ou ambígua (as versões precisam ser contíguas)"
    cp "${f[0]}" "$SEG/migracoes_montadas/migracoes/"
done
(cd "$SEG/migracoes_montadas/migracoes" && sha256sum ./*.sql) > "$SAIDA/migrations_sha256.txt"
cp "$API_REPO/db/bootstrap/aplicar_como_migrador.sh" "$SEG/migracoes_montadas/bootstrap/"
printf '#!/bin/sh\nset -e\nNEKKORA_MIGRACOES=/docker-entrypoint-initdb.d/migracoes sh /docker-entrypoint-initdb.d/bootstrap/aplicar_como_migrador.sh\n' \
    > "$SEG/migracoes_montadas/000_aplicar_como_migrador.sh"
chmod 0755 "$SEG/migracoes_montadas/000_aplicar_como_migrador.sh"
export E2E_MIGRACOES="$(cygpath -m "$SEG/migracoes_montadas" 2>/dev/null || echo "$SEG/migracoes_montadas")"
export E2E_TLS="$(cygpath -m "$SEG/tls" 2>/dev/null || echo "$SEG/tls")"

aleatorio() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }
senha() { head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }
umask 077
{
    echo "E2E_PROJETO=$E2E_PROJETO"; echo "E2E_PORTA=$E2E_PORTA"; echo "E2E_PG_PORTA=$E2E_PG_PORTA"
    echo "E2E_MIGRACOES=$E2E_MIGRACOES"; echo "E2E_TLS=$E2E_TLS"
    echo "E2E_PG_SUPER_SENHA=$(senha)"; echo "E2E_PLATFORM_SENHA=$(senha)"; echo "E2E_PORTAL_SENHA=$(senha)"; echo "E2E_INGESTAO_SENHA=$(senha)"
    echo "E2E_MFA_CHAVES=k1:$(aleatorio 32)"; echo "E2E_HMAC_CHAVE=$(aleatorio 32)"
    echo "LAB_API_REPO=$(cygpath -m "$API_REPO" 2>/dev/null || echo "$API_REPO")"
} > "$SEG/segredos.env"
set -a; . "$SEG/segredos.env"; set +a

(cd "$SEG/tls" && MSYS2_ARG_CONV_EXCL='*' openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3 -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost" -keyout key.pem -out cert.pem 2>/dev/null) || falha "certificado TLS descartável"
umask 022

echo "== postgres e redis ($E2E_PROJETO, API $COMMIT, migrations 001-$ATE)"
"${COMPOSE[@]}" up -d postgres redis
for i in $(seq 1 120); do
    [ "$("${COMPOSE[@]}" logs postgres 2>/dev/null | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
    [ "$i" -lt 120 ] || falha "PostgreSQL não terminou a inicialização"
    sleep 2
done
"${COMPOSE[@]}" logs --no-color postgres 2>/dev/null | grep "aplicar_como_migrador" > "$SAIDA/aplicar_como_migrador.txt" || true
grep -q "aplicar_como_migrador: FALHA" "$SAIDA/aplicar_como_migrador.txt" && falha "migrador falhou (aplicar_como_migrador.txt)"
grep -Eq "aplicar_como_migrador: fim db=nekkora aplicadas=[0-9]+ estado=0\|0\|0" "$SAIDA/aplicar_como_migrador.txt" || falha "migrador sem fim 0|0|0"
grep -q "aplicar_como_migrador: invariantes_016_pos_retirada=OK" "$SAIDA/aplicar_como_migrador.txt" || falha "invariantes da 016 não conferidas"
printf "ALTER ROLE nekkora_platform PASSWORD '%s';\nALTER ROLE nekkora_portal PASSWORD '%s';\nALTER ROLE nekkora_ingestion PASSWORD '%s';\n" \
    "$E2E_PLATFORM_SENHA" "$E2E_PORTAL_SENHA" "$E2E_INGESTAO_SENHA" | "${COMPOSE[@]}" exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U nekkora -d nekkora
"${COMPOSE[@]}" exec -T postgres psql -X -At -U nekkora -d nekkora -c "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = 'nekkora'" \
    > "$SAIDA/marcador_inicializacao.txt"

echo "== platform, portal, canal do Agent e nginx"
"${COMPOSE[@]}" up -d --build platform portal agent-gateway nginx
BASE="https://localhost:$E2E_PORTA"
CA="$SEG/tls/cert.pem"
for i in $(seq 1 120); do
    a="$(curl -s --cacert "$CA" -o /dev/null -w '%{http_code}' "$BASE/api/v1/csrf" || true)"
    g="$(curl -s --cacert "$CA" -o /dev/null -w '%{http_code}' "$BASE/agent/api/v1/authorizations" || true)"
    [ "$a" = "200" ] && [ "$g" = "400" ] && break
    [ "$i" -lt 120 ] || { "${COMPOSE[@]}" logs --no-color > "$SAIDA/logs_falha_subida.txt"; falha "aplicações não responderam (platform=$a agente=$g)"; }
    sleep 2
done
rm -f "$SEG/tls/key.pem" # o nginx já carregou; a chave não fica em disco

{
    echo "id=$ID"; echo "projeto=$E2E_PROJETO"; echo "api_commit=$COMMIT"; echo "migrations=001-$ATE"
    echo "origem=$BASE"; echo "certificado_sha256=$(sha256sum "$CA" | cut -c1-64)"
    echo "subido_em=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$SAIDA/laboratorio.txt"
cp "$CA" "$SAIDA/certificado-laboratorio.pem" # público (só o certificado; a chave já foi apagada)
echo "LABORATORIO OK: $BASE (estado: $SAIDA; segredos fora do repositório)"

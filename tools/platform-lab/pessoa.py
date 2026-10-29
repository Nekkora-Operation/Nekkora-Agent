"""AGENT-PLATFORM-001 — o lado da PESSOA no laboratório descartável, pela Platform REAL (como um navegador: CSRF, cookies,
TOTP e step-up), mais as fixtures DECLARADAS que a API ainda não tem fluxo para criar (o mesmo que o AgentChannelE2E faz):
identidade de laboratório, limites ilimitados da organização, workspace, automação e versão PUBLICADA com
command_json.agent_package {fingerprint_rule, fingerprint} = a impressão do pacote de laboratório calculada pelo Agent.

Segredos (senha, fator TOTP, código de pareamento, cookies) ficam só em ~/.nekkora/agent-lab/<id>/ (600) e nunca vão para a
saída nem para as evidências. O código de pareamento vai para um arquivo que a jornada do Agent consome e apaga.

Uso (python -I):
  pessoa.py preparar  <id> <impressao-64-hex>      identidade, organização, Worker, autorização e código de pareamento
  pessoa.py consultar <id> <agentExecutionId>      execução na Platform (correlação, desfecho, completude, eventos)
  pessoa.py revogar   <id>                         revoga o Agent (ADMINISTRATIVE, com step-up)
  pessoa.py autorizar <id> <impressao-64-hex>      publica outra versão com esta impressão e autoriza o mesmo Worker
  pessoa.py novo-codigo <id>                       revoga o Agent ativo (libera o Worker) e gera novo código de pareamento
  pessoa.py rotacao   <id>                         gera um código de rotação (arquivo, como o de pareamento)
  pessoa.py sql       <id>                         lê SQL da entrada padrão e roda como administrador do banco descartável
"""
import base64
import hashlib
import hmac
import http.client
import json
import os
import secrets
import ssl
import struct
import subprocess
import sys
import time
import urllib.parse
import uuid
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
AQUI = Path(__file__).resolve().parent


def lab(i):
    seg = Path.home() / '.nekkora' / 'agent-lab' / i
    env = dict(l.split('=', 1) for l in (seg / 'segredos.env').read_text(encoding='utf-8').splitlines() if '=' in l)
    return seg, env, AQUI / 'saidas' / i


def winpath(p):
    # caminho do Git Bash (/c/Users/...) → C:/Users/... para o Windows
    if len(p) > 2 and p[0] == '/' and p[2] == '/' and p[1].isalpha():
        return p[1].upper() + ':' + p[2:]
    return p


def compose(env):
    api = winpath(env['LAB_API_REPO'])
    full = dict(os.environ)
    full.update(env)
    cmd = ['docker', 'compose', '-f', str(Path(api) / 'api' / 'e2e' / 'compose.e2e.yml'), '-f', str(AQUI / 'compose.lab.yml')]
    if env.get('LAB_COMPOSE_EXTRA'):
        cmd += ['-f', str(AQUI / env['LAB_COMPOSE_EXTRA'])]
    return cmd, full


def sql(env, text):
    cmd, full = compose(env)
    r = subprocess.run(cmd + ['exec', '-T', 'postgres', 'psql', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-U', 'nekkora', '-d', 'nekkora'],
                       input=text, capture_output=True, text=True, encoding='utf-8', env=full)
    if r.returncode != 0:
        raise SystemExit('SQL falhou: ' + r.stderr.strip().splitlines()[-1][:200])
    return r.stdout.strip()


def save_private(path, data):
    path.write_text(data, encoding='utf-8')
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


class Browser:
    """Cliente na origem pública (nginx do laboratório), confiando SÓ no certificado do laboratório."""

    def __init__(self, port, cafile):
        self.port = port
        self.ctx = ssl.create_default_context(cafile=str(cafile))
        self.cookies = {}
        self.csrf = None
        self.origin = f'https://localhost:{port}'

    def call(self, method, path, body=None):
        c = http.client.HTTPSConnection('localhost', self.port, context=self.ctx, timeout=30)
        h = {'Origin': self.origin, 'Accept': 'application/json'}
        if body is not None:
            h['Content-Type'] = 'application/json'
        if self.cookies:
            h['Cookie'] = '; '.join(f'{k}={v}' for k, v in self.cookies.items())
        if self.csrf:
            h['X-CSRF-Token'] = self.csrf
        c.request(method, path, body=None if body is None else json.dumps(body), headers=h)
        r = c.getresponse()
        data = r.read().decode('utf-8')
        for k, v in r.getheaders():
            if k.lower() == 'set-cookie':
                kv = v.split(';', 1)[0].split('=', 1)
                if 'Max-Age=0' in v:
                    self.cookies.pop(kv[0].strip(), None)
                else:
                    self.cookies[kv[0].strip()] = kv[1].strip()
        c.close()
        try:
            j = json.loads(data) if data else {}
        except ValueError:
            j = {}
        return r.status, j


class Totp:
    def __init__(self, secret_b32, last=0):
        self.key = base64.b32decode(secret_b32 + '=' * (-len(secret_b32) % 8))
        self.last = last

    def code(self):
        while True:
            now = int(time.time()) // 30
            cand = max(now - 1, self.last + 1)
            if cand <= now + 1:
                self.last = cand
                h = hmac.new(self.key, struct.pack('>Q', cand), hashlib.sha1).digest()
                o = h[-1] & 0x0F
                return '%06d' % ((struct.unpack('>I', h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)
            time.sleep(1)


def expect(st, want, what):
    if st != want:
        raise SystemExit(f'{what}: HTTP {st} (esperado {want})')


def bcprov():
    base = Path.home() / '.m2' / 'repository' / 'org' / 'bouncycastle' / 'bcprov-jdk18on'
    for v in ('1.80.2', '1.80', '1.86'):
        j = base / v / f'bcprov-jdk18on-{v}.jar'
        if j.exists():
            return j
    raise SystemExit('bcprov-jdk18on ausente no cache Maven')


def login(seg, env, p):
    b = Browser(int(env['E2E_PORTA']), seg / 'tls' / 'cert.pem')
    t = Totp(p['totp'], p.get('last', 0))
    st, j = b.call('GET', '/api/v1/csrf')
    expect(st, 200, 'csrf')
    b.csrf = j['csrfToken']
    st, j = b.call('POST', '/api/v1/auth/login', {'email': p['email'], 'password': p['senha']})
    expect(st, 200, 'login')
    b.csrf = j.get('csrfToken', b.csrf)
    if j.get('status') == 'mfa_required':
        st, j = b.call('POST', '/api/v1/auth/mfa/verify', {'code': t.code()})
        expect(st, 200, 'mfa')
        b.csrf = j.get('csrfToken', b.csrf)
    st, j = b.call('POST', '/api/v1/sessions/organization', {'organizationId': p['org']})
    expect(st, 200, 'sessão da organização')
    b.csrf = j['csrfToken']
    st, _ = b.call('POST', f"/api/v1/o/{p['org']}/auth/mfa/step-up", {'code': t.code()})
    expect(st, 204, 'step-up')
    p['last'] = t.last
    save_private(seg / 'pessoa.json', json.dumps(p))
    return b


def preparar(i, fingerprint):
    seg, env, out = lab(i)
    if (seg / 'pessoa.json').exists():
        raise SystemExit('pessoa já preparada neste laboratório')
    if len(fingerprint) != 64 or any(ch not in '0123456789abcdef' for ch in fingerprint):
        raise SystemExit('impressão: 64 hex minúsculos (sem sha256:)')
    ident = str(uuid.uuid4())
    email = f'titular-{ident[:8]}@agentlab.test'
    senha = 'lab-' + secrets.token_urlsafe(18)
    r = subprocess.run(['java', '-cp', str(bcprov()), str(AQUI / 'GerarHash.java')], input=senha + '\n', capture_output=True, text=True, encoding='utf-8')
    if r.returncode != 0 or not r.stdout.startswith('$argon2id$'):
        raise SystemExit('hash da senha falhou')
    sql(env, f"INSERT INTO identities (identity_id, email, display_name, status) VALUES ('{ident}', '{email}', 'Titular do laboratório', 1);\n"
             f"UPDATE identities SET email_verified_at = now(), status = 2 WHERE identity_id = '{ident}';\n"
             f"INSERT INTO identity_credentials (identity_id, password_hash) VALUES ('{ident}', '{r.stdout.strip()}');\n")
    b = Browser(int(env['E2E_PORTA']), seg / 'tls' / 'cert.pem')
    st, j = b.call('GET', '/api/v1/csrf')
    expect(st, 200, 'csrf')
    b.csrf = j['csrfToken']
    st, j = b.call('POST', '/api/v1/auth/login', {'email': email, 'password': senha})
    expect(st, 200, 'login')
    if j.get('status') != 'mfa_enrollment_required':
        raise SystemExit('login: esperado cadastro do fator')
    b.csrf = j['csrfToken']
    st, j = b.call('POST', '/api/v1/auth/mfa/enroll/start', None)
    expect(st, 200, 'mfa start')
    totp_secret = j['otpauthUri'].split('secret=')[1].split('&')[0]
    t = Totp(totp_secret)
    st, j = b.call('POST', '/api/v1/auth/mfa/enroll/confirm', {'code': t.code()})
    expect(st, 200, 'mfa confirm')
    b.csrf = j['csrfToken']
    st, _ = b.call('POST', '/api/v1/auth/step-up', {'code': t.code()})
    expect(st, 204, 'step-up da identidade')
    st, j = b.call('POST', '/api/v1/organizations', {'name': 'Org laboratório Agent'})
    expect(st, 201, 'organização')
    org = j['organizationId']
    user = sql(env, f"SELECT u.user_id FROM users u JOIN identity_users iu ON iu.user_id = u.user_id WHERE iu.identity_id = '{ident}'")
    member = sql(env, f"SELECT membership_id FROM organization_memberships WHERE user_id = '{user}'")
    ws, auto, ver = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    limits = ''.join(
        f"INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, "
        f"provisioned_by_user_id) VALUES ('{org}', {c}, (SELECT coalesce(max(revision_number), 0) + 1 FROM organization_resource_limit_revisions "
        f"WHERE organization_id = '{org}' AND resource_code = {c}), clock_timestamp(), 2, NULL, '{user}');\n" for c in range(1, 6))
    # fixture DECLARADA: a API ainda não tem fluxo de publicação de versão (contrato, sec. 6.4); a impressão é a do Agent
    command = (f"jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','laboratorio-obs-studio','sha256','{'0' * 64}'),"
               f"'agent_package',jsonb_build_object('fingerprint_rule','nekkora-agent/package-fingerprint/1','fingerprint','{fingerprint}'),"
               f"'runtime','python3.12','entrypoint','main.py','arguments','[]'::jsonb,'parameters','{{}}'::jsonb,'secret_bindings','{{}}'::jsonb)")
    sql(env, "BEGIN;\n" f"SELECT set_config('nekkora.organization_id', '{org}', true);\n" + limits +
             f"INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('{ws}', '{org}', 'WS laboratório', 1);\n"
             f"INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) "
             f"VALUES ('{auto}', '{org}', '{ws}', 'Laboratório OBS (formato Studio)', 1, '{member}');\n"
             f"INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
             f"VALUES ('{ver}', '{org}', '{ws}', '{auto}', 1, {command}, '{member}');\n"
             f"UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '{member}' WHERE automation_version_id = '{ver}';\n"
             "COMMIT;\n")
    # sessão da organização e step-up (atos sensíveis da pessoa)
    ident_cookie = b.cookies.get('__Host-nekkora_id')
    ob = Browser(int(env['E2E_PORTA']), seg / 'tls' / 'cert.pem')
    ob.cookies['__Host-nekkora_id'] = ident_cookie
    ob.csrf = b.csrf
    st, j = ob.call('POST', '/api/v1/sessions/organization', {'organizationId': org})
    expect(st, 200, 'sessão da organização')
    ob.csrf = j['csrfToken']
    st, _ = ob.call('POST', f'/api/v1/o/{org}/auth/mfa/step-up', {'code': t.code()})
    expect(st, 204, 'step-up da organização')
    st, j = ob.call('POST', f'/api/v1/o/{org}/agent-enrollments', {'workerName': 'Estação laboratório', 'agentName': 'Agent laboratório', 'expiresInMinutes': 60})
    expect(st, 201, 'código de pareamento')
    worker, code = j['workerId'], j['code']
    st, _ = ob.call('PUT', f'/api/v1/o/{org}/workers/{worker}/workspaces/{ws}')
    if st not in (200, 201):
        raise SystemExit(f'Worker no workspace: HTTP {st}')
    until = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + 30 * 86400))
    st, j = ob.call('POST', f'/api/v1/o/{org}/workspaces/{ws}/local-run-authorizations', {'workerId': worker, 'automationVersionId': ver, 'validUntil': until})
    expect(st, 201, 'autorização de execução local')
    authz = j['authorizationId']
    save_private(seg / 'pessoa.json', json.dumps({'email': email, 'senha': senha, 'totp': totp_secret, 'last': t.last, 'org': org, 'workspace': ws,
                                                   'automation': auto, 'version': ver, 'worker': worker, 'authorization': authz}))
    save_private(seg / 'codigo-pareamento', code)
    out.mkdir(parents=True, exist_ok=True)
    resumo = {'organizacao': org, 'workspace': ws, 'automacao': auto, 'versao_publicada': ver, 'worker': worker, 'autorizacao': authz,
              'impressao_autorizada': fingerprint, 'regra': 'nekkora-agent/package-fingerprint/1', 'valida_ate': until,
              'codigo_pareamento': '<arquivo protegido fora do repositório; consumido pela jornada>'}
    (out / 'pessoa-preparacao.json').write_text(json.dumps(resumo, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps(resumo, indent=2, ensure_ascii=False))


def consultar(i, execution):
    seg, env, out = lab(i)
    p = json.loads((seg / 'pessoa.json').read_text(encoding='utf-8'))
    b = login(seg, env, p)
    st, j = b.call('GET', f"/api/v1/o/{p['org']}/agent-runs?limit=100")
    expect(st, 200, 'lista de execuções')
    run = next((x for x in j.get('items', []) if (x.get('correlation') or {}).get('agentExecutionId') == execution), None)
    if run is None:
        raise SystemExit('execução não encontrada na Platform')
    st, detail = b.call('GET', f"/api/v1/o/{p['org']}/agent-runs/{run['runId']}")
    expect(st, 200, 'detalhe')
    # a rota pagina por cursor opaco (nextCursor), até 100 por página (o contrato, sec. 10, escreve ?afterSequence=)
    events, cursor = [], None
    for _ in range(500):
        q = f"?limit=100" + (f"&cursor={urllib.parse.quote(cursor)}" if cursor else "")
        st, page = b.call('GET', f"/api/v1/o/{p['org']}/agent-runs/{run['runId']}/events{q}")
        expect(st, 200, 'eventos')
        events.extend(page.get('items', []))
        cursor = page.get('nextCursor')
        if not cursor:
            break
    seqs = [e.get('sequence') for e in events]
    doc = {'detalhe': detail, 'eventos': {'quantidade': len(events), 'primeira': seqs[0] if seqs else None, 'ultima': seqs[-1] if seqs else None,
                                          'sequencias_repetidas': len(seqs) - len(set(seqs)), 'em_ordem': seqs == sorted(seqs),
                                          'codigos': sorted({e.get('eventCode') for e in events if e.get('eventCode')})}}
    stamp = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
    out.mkdir(parents=True, exist_ok=True)
    (out / f'platform-{execution}-{stamp}.json').write_text(json.dumps(doc, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps(doc, indent=2, ensure_ascii=False))


def revogar(i):
    seg, env, out = lab(i)
    p = json.loads((seg / 'pessoa.json').read_text(encoding='utf-8'))
    b = login(seg, env, p)
    st, j = b.call('GET', f"/api/v1/o/{p['org']}/agents")
    expect(st, 200, 'agents')
    ativos = [a for a in j.get('items', []) if a.get('status') in ('ACTIVE', 2)]
    if len(ativos) != 1:
        raise SystemExit(f'esperado 1 Agent ativo, há {len(ativos)}')
    agent = ativos[0]['agentId']
    st, _ = b.call('POST', f"/api/v1/o/{p['org']}/agents/{agent}/revoke", {'reason': 'ADMINISTRATIVE'})
    expect(st, 204, 'revogação')
    print(json.dumps({'agentRevogado': agent, 'motivo': 'ADMINISTRATIVE'}, indent=2))


def autorizar(i, fingerprint):
    seg, env, out = lab(i)
    p = json.loads((seg / 'pessoa.json').read_text(encoding='utf-8'))
    if len(fingerprint) != 64 or any(ch not in '0123456789abcdef' for ch in fingerprint):
        raise SystemExit('impressão: 64 hex minúsculos (sem sha256:)')
    member = sql(env, f"SELECT m.membership_id FROM organization_memberships m JOIN identity_users iu ON iu.user_id = m.user_id "
                      f"JOIN identities i ON i.identity_id = iu.identity_id WHERE i.email = '{p['email']}' AND m.organization_id = '{p['org']}'")
    ver = str(uuid.uuid4())
    command = (f"jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','laboratorio-obs-studio','sha256','{'0' * 64}'),"
               f"'agent_package',jsonb_build_object('fingerprint_rule','nekkora-agent/package-fingerprint/1','fingerprint','{fingerprint}'),"
               f"'runtime','python3.12','entrypoint','main.py','arguments','[]'::jsonb,'parameters','{{}}'::jsonb,'secret_bindings','{{}}'::jsonb)")
    sql(env, "BEGIN;\n" f"SELECT set_config('nekkora.organization_id', '{p['org']}', true);\n"
             f"INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
             f"VALUES ('{ver}', '{p['org']}', '{p['workspace']}', '{p['automation']}', 1, {command}, '{member}');\n"
             f"UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '{member}' WHERE automation_version_id = '{ver}';\n"
             "COMMIT;\n")
    b = login(seg, env, p)
    until = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + 30 * 86400))
    st, j = b.call('POST', f"/api/v1/o/{p['org']}/workspaces/{p['workspace']}/local-run-authorizations",
                   {'workerId': p['worker'], 'automationVersionId': ver, 'validUntil': until})
    expect(st, 201, 'autorização de execução local')
    resumo = {'versao_publicada': ver, 'autorizacao': j['authorizationId'], 'impressao_autorizada': fingerprint, 'valida_ate': until}
    (out / f'pessoa-autorizacao-{fingerprint[:12]}.json').write_text(json.dumps(resumo, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(resumo, indent=2))


def novo_codigo(i):
    seg, env, out = lab(i)
    p = json.loads((seg / 'pessoa.json').read_text(encoding='utf-8'))
    b = login(seg, env, p)
    st, j = b.call('GET', f"/api/v1/o/{p['org']}/agents")
    expect(st, 200, 'agents')
    revogados = []
    for a in j.get('items', []):
        if a.get('status') in ('ACTIVE', 2):
            st, _ = b.call('POST', f"/api/v1/o/{p['org']}/agents/{a['agentId']}/revoke", {'reason': 'ADMINISTRATIVE'})
            expect(st, 204, 'revogação')
            revogados.append(a['agentId'])
    st, j = b.call('POST', f"/api/v1/o/{p['org']}/agent-enrollments", {'workerId': p['worker'], 'agentName': 'Agent laboratório', 'expiresInMinutes': 60})
    expect(st, 201, 'código de pareamento')
    save_private(seg / 'codigo-pareamento', j['code'])
    print(json.dumps({'agentsRevogados': revogados, 'worker': p['worker'], 'codigo': '<arquivo protegido fora do repositório>'}, indent=2))


def rotacao(i):
    seg, env, out = lab(i)
    p = json.loads((seg / 'pessoa.json').read_text(encoding='utf-8'))
    b = login(seg, env, p)
    st, j = b.call('GET', f"/api/v1/o/{p['org']}/agents")
    agent = [a for a in j.get('items', []) if a.get('status') in ('ACTIVE', 2)][0]['agentId']
    st, j = b.call('POST', f"/api/v1/o/{p['org']}/agents/{agent}/credential-rotations", {'expiresInMinutes': 30})
    expect(st, 201, 'código de rotação')
    save_private(seg / 'codigo-rotacao', j['code'])
    print(json.dumps({'codigoRotacao': '<arquivo protegido fora do repositório>'}))


if __name__ == '__main__':
    a = sys.argv[1:]
    if not a:
        raise SystemExit(__doc__)
    if a[0] == 'preparar':
        preparar(a[1], a[2])
    elif a[0] == 'consultar':
        consultar(a[1], a[2])
    elif a[0] == 'revogar':
        revogar(a[1])
    elif a[0] == 'autorizar':
        autorizar(a[1], a[2])
    elif a[0] == 'novo-codigo':
        novo_codigo(a[1])
    elif a[0] == 'rotacao':
        rotacao(a[1])
    elif a[0] == 'sql':
        print(sql(lab(a[1])[1], sys.stdin.read()))
    else:
        raise SystemExit(__doc__)

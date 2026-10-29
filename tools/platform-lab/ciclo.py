"""PLATFORM-AUTOMATION-LIFECYCLE-001 — a PESSOA no laboratório, pela Platform REAL, SEM fixture de publicação (python -I).

Fixtures DECLARADAS só para o que é configuração do laboratório: a identidade da pessoa (senha conhecida) e os limites da
organização (a 010 exige revisão para registrar Worker). Tudo o mais pelas rotas reais, como uma pessoa no navegador
(CSRF, cookies, TOTP, step-up): organização, workspace, automação, versão DRAFT, ENVIO do pacote do Studio (ZIP), conferência
pela API, PUBLICAÇÃO, código de pareamento, Worker no workspace e autorização de execução local da versão publicada.

Segredos (senha, fator TOTP, código de pareamento, cookies) ficam em ~/.nekkora/agent-lab/<id>/ e nunca vão para a saída.

Uso:
  ciclo.py preparar <id> <zip-do-pacote>     do zero até a autorização (grava pessoa.json, compatível com pessoa.py)
  ciclo.py consultar <id> <agentExecutionId>  a execução na Platform (correlação, desfecho, completude, eventos)
"""
import hashlib
import http.client
import json
import secrets
import subprocess
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pessoa  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8')


def put_zip(b, path, data):
    """PUT application/zip pela origem pública, com os cookies e o CSRF da sessão (o Browser do pessoa.py só manda JSON)."""
    c = http.client.HTTPSConnection('localhost', b.port, context=b.ctx, timeout=120)
    h = {'Origin': b.origin, 'Accept': 'application/json', 'Content-Type': 'application/zip', 'Content-Length': str(len(data)),
         'Cookie': '; '.join(f'{k}={v}' for k, v in b.cookies.items()), 'X-CSRF-Token': b.csrf}
    c.request('PUT', path, body=data, headers=h)
    r = c.getresponse()
    body = r.read().decode('utf-8')
    c.close()
    try:
        return r.status, json.loads(body) if body else {}
    except ValueError:
        return r.status, {}


def preparar(i, zip_path):
    seg, env, out = pessoa.lab(i)
    if (seg / 'pessoa.json').exists():
        raise SystemExit('pessoa já preparada neste laboratório')
    data = Path(zip_path).read_bytes()
    ident = str(uuid.uuid4())
    email = f'titular-{ident[:8]}@agentlab.test'
    senha = 'lab-' + secrets.token_urlsafe(18)
    r = subprocess.run(['java', '-cp', str(pessoa.bcprov()), str(pessoa.AQUI / 'GerarHash.java')], input=senha + '\n', capture_output=True, text=True,
                       encoding='utf-8')
    if r.returncode != 0 or not r.stdout.startswith('$argon2id$'):
        raise SystemExit('hash da senha falhou')
    # FIXTURE DECLARADA 1: identidade de laboratório (configuração; o cadastro web não está ligado nesta stack)
    pessoa.sql(env, f"INSERT INTO identities (identity_id, email, display_name, status) VALUES ('{ident}', '{email}', 'Titular do laboratório', 1);\n"
                    f"UPDATE identities SET email_verified_at = now(), status = 2 WHERE identity_id = '{ident}';\n"
                    f"INSERT INTO identity_credentials (identity_id, password_hash) VALUES ('{ident}', '{r.stdout.strip()}');\n")
    b = pessoa.Browser(int(env['E2E_PORTA']), seg / 'tls' / 'cert.pem')
    st, j = b.call('GET', '/api/v1/csrf')
    pessoa.expect(st, 200, 'csrf')
    b.csrf = j['csrfToken']
    st, j = b.call('POST', '/api/v1/auth/login', {'email': email, 'password': senha})
    pessoa.expect(st, 200, 'login')
    b.csrf = j['csrfToken']
    st, j = b.call('POST', '/api/v1/auth/mfa/enroll/start', None)
    pessoa.expect(st, 200, 'mfa start')
    totp_secret = j['otpauthUri'].split('secret=')[1].split('&')[0]
    t = pessoa.Totp(totp_secret)
    st, j = b.call('POST', '/api/v1/auth/mfa/enroll/confirm', {'code': t.code()})
    pessoa.expect(st, 200, 'mfa confirm')
    b.csrf = j['csrfToken']
    st, _ = b.call('POST', '/api/v1/auth/step-up', {'code': t.code()})
    pessoa.expect(st, 204, 'step-up da identidade')
    st, j = b.call('POST', '/api/v1/organizations', {'name': 'Org laboratório ciclo de vida'})
    pessoa.expect(st, 201, 'organização')
    org = j['organizationId']
    user = pessoa.sql(env, f"SELECT u.user_id FROM users u JOIN identity_users iu ON iu.user_id = u.user_id WHERE iu.identity_id = '{ident}'")
    # FIXTURE DECLARADA 2: limites da organização (configuração; a ativação comercial não faz parte desta missão)
    limits = ''.join(
        f"INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, "
        f"provisioned_by_user_id) VALUES ('{org}', {c}, (SELECT coalesce(max(revision_number), 0) + 1 FROM organization_resource_limit_revisions "
        f"WHERE organization_id = '{org}' AND resource_code = {c}), clock_timestamp(), 2, NULL, '{user}');\n" for c in range(1, 6))
    pessoa.sql(env, "BEGIN;\n" f"SELECT set_config('nekkora.organization_id', '{org}', true);\n" + limits + "COMMIT;\n")
    ob = pessoa.Browser(int(env['E2E_PORTA']), seg / 'tls' / 'cert.pem')
    ob.cookies['__Host-nekkora_id'] = b.cookies.get('__Host-nekkora_id')
    ob.csrf = b.csrf
    st, j = ob.call('POST', '/api/v1/sessions/organization', {'organizationId': org})
    pessoa.expect(st, 200, 'sessão da organização')
    ob.csrf = j['csrfToken']
    o = f'/api/v1/o/{org}'
    # ---- ciclo de vida pelas rotas reais (nenhum INSERT em workspace, automação ou versão) ----
    st, j = ob.call('POST', f'{o}/workspaces', {'name': 'WS ciclo de vida'})
    pessoa.expect(st, 201, 'workspace')
    ws = j['workspaceId']
    st, j = ob.call('POST', f'{o}/workspaces/{ws}/automations', {'name': 'Biografias da Wikipedia (Studio)'})
    pessoa.expect(st, 201, 'automação')
    auto = j['automationId']
    st, j = ob.call('POST', f'{o}/automations/{auto}/versions', {'runtime': 'python3.12', 'arguments': []})
    pessoa.expect(st, 201, 'versão DRAFT')
    ver = j['automationVersionId']
    st, j = put_zip(ob, f'{o}/automation-versions/{ver}/artifact', data)
    if st != 200:
        raise SystemExit(f'artefato: HTTP {st} {json.dumps(j)}')
    art = j['artifact']
    sha_local = hashlib.sha256(data).hexdigest()
    if art['sha256'] != sha_local:
        raise SystemExit('sha256 do servidor diferente do local')
    st, _ = ob.call('POST', f'{o}/auth/mfa/step-up', {'code': t.code()})
    pessoa.expect(st, 204, 'step-up da organização')
    st, j = ob.call('POST', f'{o}/automation-versions/{ver}/publish', {'expectedArtifactSha256': art['sha256'], 'expectedPackageFingerprint': art['fingerprint']})
    pessoa.expect(st, 200, 'publicação')
    publicada = j
    # publicar não executa nem autoriza: nada no canal ainda
    antes = pessoa.sql(env, f"SELECT (SELECT count(*) FROM agent_runs WHERE organization_id = '{org}') || '|' || "
                            f"(SELECT count(*) FROM local_run_authorizations WHERE organization_id = '{org}') || '|' || "
                            f"(SELECT count(*) FROM executions WHERE organization_id = '{org}')")
    st, j = ob.call('POST', f'{o}/agent-enrollments', {'workerName': 'Estação laboratório', 'agentName': 'Agent laboratório', 'expiresInMinutes': 60})
    pessoa.expect(st, 201, 'código de pareamento')
    worker, code = j['workerId'], j['code']
    st, _ = ob.call('PUT', f'{o}/workers/{worker}/workspaces/{ws}')
    if st not in (200, 201):
        raise SystemExit(f'Worker no workspace: HTTP {st}')
    until = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + 30 * 86400))
    st, j = ob.call('POST', f'{o}/workspaces/{ws}/local-run-authorizations', {'workerId': worker, 'automationVersionId': ver, 'validUntil': until})
    pessoa.expect(st, 201, 'autorização de execução local')
    authz = j['authorizationId']
    if j['packageFingerprint'] != art['fingerprint']:
        raise SystemExit('a autorização não carrega a impressão da versão publicada')
    member = pessoa.sql(env, f"SELECT membership_id FROM organization_memberships WHERE user_id = '{user}'")
    trilha = pessoa.sql(env, f"SELECT string_agg(act::text, ',' ORDER BY created_at) FROM automation_admin_events WHERE organization_id = '{org}'")
    fixtures_publicacao = pessoa.sql(env, f"SELECT count(*) FROM automation_versions v WHERE v.organization_id = '{org}' AND v.status = 2 AND NOT EXISTS "
                                          f"(SELECT 1 FROM automation_admin_events e WHERE e.automation_version_id = v.automation_version_id AND e.act = 6)")
    pessoa.save_private(seg / 'pessoa.json', json.dumps({'email': email, 'senha': senha, 'totp': totp_secret, 'last': t.last, 'org': org, 'workspace': ws,
                                                          'automation': auto, 'version': ver, 'worker': worker, 'authorization': authz}))
    pessoa.save_private(seg / 'codigo-pareamento', code)
    out.mkdir(parents=True, exist_ok=True)
    resumo = {'organizacao': org, 'workspace': ws, 'automacao': auto, 'versao_publicada': ver, 'numero_da_versao': publicada['versionNumber'],
              'publicada_por_membership': publicada['publishedByMembershipId'], 'membership_da_pessoa': member, 'publicada_em': publicada['publishedAt'],
              'artefato': {k: art[k] for k in ('artifactId', 'sha256', 'sizeBytes', 'fingerprintRule', 'fingerprint', 'filesCount', 'manifestVersion',
                                               'framework', 'executionMode', 'declaredGenerator', 'provenance')},
              'artifact_reference': publicada['artifactReference'], 'sha256_local_do_zip': sha_local,
              'worker': worker, 'autorizacao': authz, 'impressao_autorizada': art['fingerprint'], 'valida_ate': until,
              'canal_antes_da_autorizacao_agent_runs_autorizacoes_execucoes': antes, 'trilha_atos': trilha,
              'versoes_publicadas_sem_ato_de_publicacao_da_api': fixtures_publicacao,
              'fixtures_declaradas': ['identidade da pessoa', 'limites da organizacao (010)'],
              'codigo_pareamento': '<arquivo protegido fora do repositório; consumido pela jornada>'}
    (out / 'ciclo-preparacao.json').write_text(json.dumps(resumo, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps(resumo, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    if sys.argv[1] == 'preparar':
        preparar(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == 'consultar':
        pessoa.consultar(sys.argv[2], sys.argv[3])
    else:
        raise SystemExit(__doc__)

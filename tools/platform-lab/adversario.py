"""INTEGRATION-CONSOLIDATION-001, C4 itens 8 e 9 — provas adversariais contra a API REAL do laboratório (python -I).

Uma SEGUNDA máquina de laboratório ("adversária"), pareada pelas rotas reais (a pessoa gera o código com step-up; o
segredo da credencial é gerado aqui e fica só na memória), num SEGUNDO Worker da mesma organização. Ela tenta o que o
Agent nunca faz e confere a recusa do servidor:
  A1 listar autorizações: não vê a autorização do Worker do Agent          (isolamento de Worker)
  A2 registrar sob a autorização do outro Worker                         -> 403
  A3 registrar sob autorização que não é da organização                 -> 403 (a RLS esconde)
  A4 registrar sob a própria autorização; lote 1..3                      -> STORED
  A5 reenviar 1..3 iguais                                                -> DUPLICATE (idempotente)
  A6 sequence 2 com conteúdo adulterado                                  -> CONFLICT (o original fica e conta)
  A7 evento com campo fora do envelope (texto livre)                     -> INVALID
  A8 credencial vencida (expires_at no passado, declarado no banco)      -> 401 e nada aceito
  A9 rota da máquina sem cabeçalho de contrato / contrato 2              -> 400 unsupported_contract
  A10 trilha auditável: o que o banco gravou (contagens, sem conteúdo)
Nenhum segredo vai para a saída. Uso: adversario.py <id> <impressao-64-hex>
"""
import json
import secrets
import sys
import time
import uuid

sys.path.insert(0, __file__.rsplit('\\', 1)[0] if '\\' in __file__ else __file__.rsplit('/', 1)[0])
import pessoa  # noqa: E402

CONTRATO = '1.0'


def maquina(port, cafile, token=None, contrato=CONTRATO):
    import http.client
    import ssl
    ctx = ssl.create_default_context(cafile=str(cafile))

    def call(method, path, body=None):
        c = http.client.HTTPSConnection('localhost', port, context=ctx, timeout=30)
        h = {'Accept': 'application/json', 'User-Agent': 'nekkora-agent/adversario-lab'}
        if contrato:
            h['Nekkora-Agent-Contract'] = contrato
        if token:
            h['Authorization'] = 'Bearer ' + token
        if body is not None:
            h['Content-Type'] = 'application/json'
        c.request(method, path, body=None if body is None else json.dumps(body), headers=h)
        r = c.getresponse()
        data = r.read().decode('utf-8')
        c.close()
        try:
            return r.status, json.loads(data) if data else {}
        except ValueError:
            return r.status, {}
    return call


def envelope(ex, seq, code='STEP_COMPLETED', extra=None):
    e = {'schema': 'nekkora-agent/observability', 'version': '1.0.0', 'agent_execution_id': ex, 'sequence': seq,
         'timestamp_utc': '2026-10-29T12:00:00.000Z', 'observed_utc': '2026-10-29T12:00:01.000Z', 'source': 'AGENT',
         'event_code': code, 'severity': 'INFO', 'category': 'ACTIVITY', 'step_id': f'passo.{seq}'}
    if extra:
        e.update(extra)
    return e


def main(i, fingerprint):
    seg, env, out = pessoa.lab(i)
    p = json.loads((seg / 'pessoa.json').read_text(encoding='utf-8'))
    port, ca = int(env['E2E_PORTA']), seg / 'tls' / 'cert.pem'
    r = {}
    ok = lambda nome, cond, obtido: r.__setitem__(nome, {'resultado': 'PASS' if cond else 'FAIL', 'obtido': obtido})

    # segundo Worker e código de pareamento pela pessoa (rotas reais, step-up)
    b = pessoa.login(seg, env, p)
    st, j = b.call('POST', f"/api/v1/o/{p['org']}/agent-enrollments", {'workerName': 'Estação adversária', 'agentName': 'Máquina adversária', 'expiresInMinutes': 15})
    pessoa.expect(st, 201, 'código da máquina adversária')
    worker2, code = j['workerId'], j['code']
    segredo = secrets.token_urlsafe(32)
    st, j = maquina(port, ca)('POST', '/agent/api/v1/enrollments/redeem',
                              {'code': code, 'credentialSecret': segredo, 'agentVersion': '0.0.0-adversario', 'contractVersions': [CONTRATO]})
    pessoa.expect(st, 200, 'resgate da máquina adversária')
    if j.get('credentialId') is None or j.get('organizationId') != p['org']:
        raise SystemExit('resgate sem credentialId/organização esperados')
    token = f"nka1.{j['organizationId']}.{j['credentialId']}.{segredo}"  # como o Agent monta (CredentialVault)
    agent2 = j['agentId']
    m = maquina(port, ca, token)

    st, j = m('GET', '/agent/api/v1/authorizations')
    ids = [z['authorizationId'] for z in j.get('items', [])]
    ok('A1-isolamento-de-worker', st == 200 and p['authorization'] not in ids, {'status': st, 'itens': len(ids)})

    sup = {'state': 'RUNNING', 'created': '2026-10-29T12:00:00Z', 'started': '2026-10-29T12:00:00.5Z'}
    ex_a = 'ex-' + secrets.token_hex(16)
    st, j = m('POST', '/agent/api/v1/runs', {'agentExecutionId': ex_a, 'authorizationId': p['authorization'], 'packageFingerprint': fingerprint, 'supervisor': sup})
    ok('A2-autorizacao-de-outro-worker', st == 403, {'status': st, 'error': j.get('error')})

    # autorização que não existe nesta organização (de outra organização a RLS a esconde do mesmo jeito: AgentAdminIT,
    # AgentChannelE2E); nenhuma fixture de outra organização é criada aqui
    st, j = m('POST', '/agent/api/v1/runs', {'agentExecutionId': 'ex-' + secrets.token_hex(16), 'authorizationId': str(uuid.uuid4()),
                                            'packageFingerprint': fingerprint, 'supervisor': sup})
    ok('A3-autorizacao-fora-da-organizacao', st == 403, {'status': st, 'error': j.get('error')})

    # a pessoa põe o Worker 2 no workspace e autoriza a mesma versão para ele (rotas reais)
    st, _ = b.call('PUT', f"/api/v1/o/{p['org']}/workers/{worker2}/workspaces/{p['workspace']}")
    until = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + 86400))
    st, j = b.call('POST', f"/api/v1/o/{p['org']}/workspaces/{p['workspace']}/local-run-authorizations",
                   {'workerId': worker2, 'automationVersionId': p['version'], 'validUntil': until})
    pessoa.expect(st, 201, 'autorização do Worker adversário')
    az = j['authorizationId']
    ex = 'ex-' + secrets.token_hex(16)
    st, j = m('POST', '/agent/api/v1/runs', {'agentExecutionId': ex, 'authorizationId': az, 'packageFingerprint': fingerprint, 'supervisor': sup})
    reg_ok = st in (200, 201)
    lote = [envelope(ex, s) for s in (1, 2, 3)]
    st, j = m('POST', f'/agent/api/v1/runs/{ex}/events', {'events': lote})
    est = [x.get('status') for x in j.get('results', [])]
    ok('A4-registro-e-lote', reg_ok and st == 200 and est == ['STORED'] * 3, {'status': st, 'resultados': est, 'stored': j.get('storedThrough')})
    st, j = m('POST', f'/agent/api/v1/runs/{ex}/events', {'events': lote})
    est = [x.get('status') for x in j.get('results', [])]
    ok('A5-reenvio-idempotente', st == 200 and est == ['DUPLICATE'] * 3, {'status': st, 'resultados': est})
    st, j = m('POST', f'/agent/api/v1/runs/{ex}/events', {'events': [envelope(ex, 2, code='ACTION_COMPLETED')]})
    res = j.get('results', [{}])[0]
    ok('A6-conteudo-adulterado', st == 200 and res.get('status') == 'CONFLICT', {'status': st, 'resultado': res})
    st, j = m('POST', f'/agent/api/v1/runs/{ex}/events', {'events': [envelope(ex, 4, extra={'message': 'texto livre proibido'})]})
    res = j.get('results', [{}])[0] if j.get('results') else {'http': st, 'error': j.get('error')}
    ok('A7-evento-invalido', res.get('status') == 'INVALID' or (st == 400 and 'results' not in j), {'status': st, 'resultado': res})

    st, j = maquina(port, ca, token, contrato=None)('POST', '/agent/api/v1/heartbeat', {'agentVersion': '0', 'contractVersion': CONTRATO, 'capabilities': []})
    st2, j2 = maquina(port, ca, token, contrato='2.0')('POST', '/agent/api/v1/heartbeat', {'agentVersion': '0', 'contractVersion': '2.0', 'capabilities': []})
    ok('A9-contrato-ausente-ou-maior', st == 400 and st2 == 400 and j.get('error') == j2.get('error') == 'unsupported_contract',
       {'sem_cabecalho': [st, j.get('error')], 'contrato_2': [st2, j2.get('error')]})

    pessoa.sql(env, f"UPDATE agent_credentials SET expires_at = now() - interval '1 second' WHERE agent_id = '{agent2}' AND revoked_at IS NULL;")
    st, j = m('POST', '/agent/api/v1/heartbeat', {'agentVersion': '0', 'contractVersion': CONTRATO, 'capabilities': []})
    st2, j2 = m('POST', f'/agent/api/v1/runs/{ex}/events', {'events': [envelope(ex, 5)]})
    ok('A8-credencial-vencida', st == 401 and st2 == 401, {'heartbeat': st, 'lote': st2})

    q = pessoa.sql(env, f"SELECT events_stored || '|' || stored_through || '|' || settled_through || '|' || conflicts FROM agent_runs WHERE agent_execution_id = '{ex}'")
    seqs = pessoa.sql(env, f"SELECT string_agg(e.sequence::text || ':' || e.event_code, ',' ORDER BY e.sequence) FROM agent_run_events e JOIN agent_runs r ON r.agent_run_id = e.agent_run_id WHERE r.agent_execution_id = '{ex}'")
    trilha = pessoa.sql(env, f"SELECT count(*) FROM agent_admin_events WHERE organization_id = '{p['org']}'")
    ok('A10-trilha-no-banco', q.startswith('3|3|3') and seqs == '1:STEP_COMPLETED,2:STEP_COMPLETED,3:STEP_COMPLETED',
       {'events_stored|stored|settled|conflicts': q, 'gravados': seqs, 'eventos_administrativos_da_org': trilha})

    # varredura: nenhum segredo desta prova na saída
    texto = json.dumps(r, ensure_ascii=False)
    for s in (segredo, token, code):
        if s and s in texto:
            raise SystemExit('segredo na saída da prova adversária')
    out.mkdir(parents=True, exist_ok=True)
    total = sum(1 for v in r.values() if v['resultado'] == 'PASS')
    resumo = {'prova': 'C4 itens 8 e 9 (API real, maquina de laboratorio adversaria)', 'placar': f'{total}/{len(r)}', 'casos': r,
              'maquina_adversaria': {'agentId': agent2, 'workerId': worker2, 'credencial': '<so na memoria; vencida no fim>'}}
    (out / 'c4-adversario.json').write_text(json.dumps(resumo, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps(resumo, indent=2, ensure_ascii=False))
    return 0 if total == len(r) else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1], sys.argv[2]))

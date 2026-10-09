import { useState } from 'react';
import { api, download, qs } from './api.js';
import { t } from './i18n.js';
import { useLoad, Flash, Table, Form, Card, Stat } from './ui.jsx';

/** Hook d'action : exécute, affiche succès/erreur, recharge. */
function useAction(reload) {
  const [msg, setMsg] = useState({});
  const run = async (fn, done) => {
    try { await fn(); setMsg({ ok: t('ok') }); done?.(); reload?.(); } catch (e) { setMsg({ error: e.message }); }
  };
  return [msg, run];
}

const pct = (x) => `${(100 * (x || 0)).toFixed(1)} %`;

export function Dashboard() {
  const [hours, setHours] = useState(24);
  const [{ data, error, loading }] = useLoad(() => api('/admin/dashboard?hours=' + hours), [hours]);
  const [msg, run] = useAction();
  return (
    <>
      <Card>
        <label>{t('hours')} <select value={hours} onChange={(e) => setHours(+e.target.value)}>{[1, 6, 24, 72, 168].map((h) => <option key={h}>{h}</option>)}</select></label>{' '}
        {['pdf', 'xlsx', 'csv'].map((f) => <button key={f} className="btn sm" onClick={() => run(() => download(`/admin/reports/summary/export?format=${f}&hours=${hours}`, `synthese.${f}`))}>{t('export')} {f.toUpperCase()}</button>)}
        <Flash {...msg} />
      </Card>
      <Flash error={error} />
      {loading && <p className="muted">{t('loading')}</p>}
      {data && (
        <>
          <div className="stats">
            <Stat label={t('pending')} value={data.pending} />
            <Stat label={t('deliveryRate')} value={pct(data.deliveryRate)} />
            {Object.entries(data.mo).map(([k, v]) => <Stat key={'mo' + k} label={'MO ' + k} value={v} />)}
            {Object.entries(data.mt).map(([k, v]) => <Stat key={'mt' + k} label={'MT ' + k} value={v} />)}
          </div>
          <Card title={t('operators')}><Table cols={['code', 'status', 'maxTps']} rows={data.operators} /></Card>
          {Object.keys(data.billing).length > 0 && <Card title={t('ledger')}><Table cols={['status', 'gross', 'partner', 'provider', 'events']} rows={Object.entries(data.billing).map(([k, v]) => ({ status: k, ...v }))} /></Card>}
        </>
      )}
    </>
  );
}

export function Operators() {
  const [{ data, error }, reload] = useLoad(() => api('/admin/operators'));
  const [msg, run] = useAction(reload);
  const edit = (o, patch) => run(() => api('/admin/operators/' + o.id, { method: 'PATCH', body: patch }));
  return (
    <Card title={t('operators')}>
      <Flash error={error} {...msg} />
      <Table cols={['code', 'name', 'status', 'maxTps', 'dlrBillingRule', 'msisdnPrefixes', t('actions')]} rows={data} render={{
        [t('actions')]: (o) => (
          <>
            <button className="btn sm" onClick={() => edit(o, { status: o.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' })}>{o.status === 'ACTIVE' ? t('suspend') : t('activate')}</button>{' '}
            <button className="btn sm" onClick={() => { const v = prompt('TPS max', o.maxTps); if (v) edit(o, { maxTps: +v }); }}>TPS</button>{' '}
            <button className="btn sm" onClick={() => { const v = prompt('Préfixes (a,b)', o.msisdnPrefixes); if (v !== null) edit(o, { msisdnPrefixes: v }); }}>Préfixes</button>{' '}
            <button className="btn sm" onClick={() => edit(o, { dlrBillingRule: o.dlrBillingRule === 'ON_DELIVERED' ? 'ON_SUBMITTED' : 'ON_DELIVERED' })}>DLR</button>
          </>) }} />
    </Card>
  );
}

export function ShortCodes() {
  const [{ data, error }, reload] = useLoad(() => api('/admin/shortcodes'));
  const [ops] = useLoad(() => api('/admin/operators'));
  const [msg, run] = useAction(reload);
  return (
    <>
      <Card title={t('shortcodes')}>
        <Flash error={error} {...msg} />
        <Table cols={['number', 'operator', 'status', t('actions')]} rows={data?.map((s) => ({ ...s, operator: s.operator?.code }))} render={{
          [t('actions')]: (s) => <button className="btn sm" onClick={() => run(() => api(`/admin/shortcodes/${s.id}/${s.status === 'ACTIVE' ? 'suspend' : 'activate'}`, { method: 'POST' }))}>{s.status === 'ACTIVE' ? t('suspend') : t('activate')}</button> }} />
      </Card>
      <Card title={t('create')}>
        <Form fields={[{ name: 'number', label: 'Short code', required: true }, { name: 'operatorCode', label: t('operators'), required: true, options: (ops.data || []).map((o) => o.code) }]}
          onSubmit={(v, done) => run(() => api('/admin/shortcodes', { method: 'POST', body: v }), done)} />
      </Card>
    </>
  );
}

export function Partners() {
  const [{ data, error }, reload] = useLoad(() => api('/admin/partners'));
  const [msg, run] = useAction(reload);
  return (
    <>
      <Card title={t('partners')}><Flash error={error} {...msg} /><Table cols={['id', 'name', 'sharePercent', 'webhookUrl']} rows={data} /></Card>
      <Card title={t('create')}>
        <Form fields={[{ name: 'name', label: t('name'), required: true }, { name: 'sharePercent', label: '% partenaire', type: 'number', step: '0.01' }, { name: 'webhookUrl', label: 'Webhook URL' }, { name: 'webhookSecret', label: 'Webhook secret', type: 'password' }]}
          onSubmit={(v, done) => run(() => api('/admin/partners', { method: 'POST', body: { ...v, sharePercent: v.sharePercent ? +v.sharePercent : 0 } }), done)} />
      </Card>
    </>
  );
}

export function Services() {
  const [{ data, error }, reload] = useLoad(() => api('/admin/services'));
  const [sc] = useLoad(() => api('/admin/shortcodes'));
  const [pa] = useLoad(() => api('/admin/partners'));
  const [sel, setSel] = useState(null);
  const [msg, run] = useAction(reload);
  const st = (s, status) => run(() => api(`/admin/services/${s.id}/status/${status}`, { method: 'POST' }));
  return (
    <>
      <Card title={t('services')}>
        <Flash error={error} {...msg} />
        <Table cols={['id', 'name', 'type', 'status', 'shortCode', 'operator', 'partner', 'regulated', 'regulatoryApproved', t('actions')]} rows={data} render={{
          [t('actions')]: (s) => (
            <>
              {s.status !== 'ACTIVE' && <button className="btn sm" onClick={() => st(s, 'ACTIVE')}>{t('activate')}</button>}{' '}
              {s.status === 'ACTIVE' && <button className="btn sm" onClick={() => st(s, 'SUSPENDED')}>{t('suspend')}</button>}{' '}
              {s.regulated && !s.regulatoryApproved && <button className="btn sm" onClick={() => run(() => api(`/admin/services/${s.id}/regulatory-approval`, { method: 'POST' }))}>{t('regulatory')}</button>}{' '}
              <button className="btn sm" onClick={() => setSel(s)}>{t('keywords')} / {t('replies')}</button>
            </>) }} />
      </Card>
      {sel && <ServiceDetail service={sel} onClose={() => setSel(null)} />}
      <Card title={t('create')}>
        <Form fields={[
          { name: 'name', label: t('name'), required: true },
          { name: 'type', label: 'Type', required: true, options: ['VOTE', 'QUIZ', 'PREMIUM_CONTENT', 'SUBSCRIPTION', 'ALERT'] },
          { name: 'shortCodeId', label: 'Short code', required: true, options: (sc.data || []).map((s) => ({ value: s.id, label: `${s.number} (${s.operator?.code})` })) },
          { name: 'partnerId', label: t('partners'), options: (pa.data || []).map((p) => ({ value: p.id, label: p.name })) },
          { name: 'consentMode', label: 'Consentement', options: ['SIMPLE_OPT_IN', 'DOUBLE_OPT_IN', 'API_ACTIVATION'] },
          { name: 'defaultLang', label: 'Langue', options: ['fr', 'ar', 'en'] },
          { name: 'maxActionsPerMsisdn', label: 'Max actions / MSISDN', type: 'number' },
          { name: 'opensAt', label: 'Ouverture', type: 'datetime-local' }, { name: 'closesAt', label: 'Fermeture', type: 'datetime-local' },
          { name: 'regulated', label: 'Réglementé', type: 'checkbox' },
        ]} onSubmit={(v, done) => run(() => api('/admin/services', { method: 'POST', body: {
          ...v, shortCodeId: +v.shortCodeId, partnerId: v.partnerId ? +v.partnerId : undefined, maxActionsPerMsisdn: v.maxActionsPerMsisdn ? +v.maxActionsPerMsisdn : undefined,
          opensAt: v.opensAt ? new Date(v.opensAt).toISOString() : undefined, closesAt: v.closesAt ? new Date(v.closesAt).toISOString() : undefined } }), done)} />
      </Card>
    </>
  );
}

function ServiceDetail({ service, onClose }) {
  const [kw, reloadKw] = useLoad(() => api('/admin/keywords?serviceId=' + service.id), [service.id]);
  const [rp, reloadRp] = useLoad(() => api('/admin/replies?serviceId=' + service.id), [service.id]);
  const [msg, run] = useAction();
  return (
    <Card title={`${service.name} — ${t('keywords')} / ${t('replies')}`}>
      <button className="btn sm" onClick={onClose}>✕</button><Flash {...msg} />
      <Table cols={['word', t('actions')]} rows={kw.data} render={{ [t('actions')]: (k) => <button className="btn sm" onClick={() => run(() => api('/admin/keywords/' + k.id, { method: 'DELETE' }), reloadKw)}>{t('delete')}</button> }} />
      <Form fields={[{ name: 'word', label: 'Mot-clé', required: true }]} onSubmit={(v, done) => run(() => api('/admin/keywords', { method: 'POST', body: { serviceId: service.id, word: v.word } }), () => { done(); reloadKw(); })} />
      <h4>{t('replies')}</h4>
      <Table cols={['lang', 'kind', 'text']} rows={rp.data} />
      <Form submit={t('save')} fields={[
        { name: 'lang', label: 'Langue', required: true, options: ['fr', 'ar', 'en'] },
        { name: 'kind', label: 'Type', required: true, options: ['OK', 'STOP', 'HELP', 'LIMIT', 'CLOSED', 'CONFIRM', 'ALREADY', 'SUB_OK', 'RENEWAL'] },
        { name: 'text', label: 'Texte', required: true }]}
        onSubmit={(v, done) => run(() => api('/admin/replies', { method: 'PUT', body: { serviceId: service.id, ...v } }), () => { done(); reloadRp(); })} />
    </Card>
  );
}

export function Tariffs() {
  const [{ data, error }, reload] = useLoad(() => api('/admin/tariffs'));
  const [sv] = useLoad(() => api('/admin/services'));
  const [sim, setSim] = useState(null);
  const [msg, run] = useAction(reload);
  return (
    <>
      <Card title={t('tariffs')}>
        <Flash error={error} {...msg} />
        <Table cols={['id', 'service', 'eventType', 'grossAmount', 'operatorPercent', 'taxPercent', 'effectiveFrom', 'approved', 'createdBy', t('actions')]} rows={data} render={{
          [t('actions')]: (x) => !x.approved && <button className="btn sm" onClick={() => run(() => api(`/admin/tariffs/${x.id}/approve`, { method: 'POST' }))}>{t('approve')}</button> }} />
      </Card>
      <Card title={t('create')}>
        <Form fields={[
          { name: 'serviceId', label: t('services'), required: true, options: (sv.data || []).map((s) => ({ value: s.id, label: s.name })) },
          { name: 'eventType', label: 'Événement', required: true, options: ['MT', 'SUBSCRIPTION', 'RENEWAL', 'MO'] },
          { name: 'grossAmount', label: 'Prix facial TTC', type: 'number', step: '0.001', required: true },
          { name: 'operatorPercent', label: '% opérateur', type: 'number', step: '0.01', required: true },
          { name: 'taxPercent', label: '% taxes', type: 'number', step: '0.01' },
          { name: 'effectiveFrom', label: 'Effet', type: 'datetime-local' }]}
          onSubmit={(v, done) => run(() => api('/admin/tariffs', { method: 'POST', body: { ...v, serviceId: +v.serviceId, grossAmount: +v.grossAmount, operatorPercent: +v.operatorPercent,
            taxPercent: v.taxPercent ? +v.taxPercent : 0, effectiveFrom: v.effectiveFrom ? new Date(v.effectiveFrom).toISOString() : undefined } }), done)} />
      </Card>
      <Card title={t('simulate')}>
        <Form submit={t('simulate')} fields={[{ name: 'gross', label: 'Prix TTC', type: 'number', step: '0.001', required: true }, { name: 'tax', label: '% taxes', type: 'number', required: true },
          { name: 'operatorPercent', label: '% opérateur', type: 'number', required: true }, { name: 'partnerPercent', label: '% partenaire', type: 'number' }]}
          onSubmit={async (v) => setSim(await api('/admin/tariffs/simulate?' + qs({ ...v, partnerPercent: v.partnerPercent || 0 })))} />
        {sim && <Table cols={['gross', 'taxes', 'operatorShare', 'partnerShare', 'providerShare']} rows={[sim]} />}
      </Card>
    </>
  );
}

export function Messages() {
  const [f, setF] = useState({});
  const [q, setQ] = useState('');
  const [{ data, error }] = useLoad(() => api('/admin/messages?' + q), [q]);
  return (
    <>
      <Card title={t('messages')}>
        <Form submit={t('search')} initial={{}} fields={[{ name: 'id', label: 'ID' }, { name: 'msisdn', label: t('msisdn') }, { name: 'operator', label: t('operators'), options: ['TT', 'ORANGE', 'OOREDOO'] },
          { name: 'shortCode', label: 'Short code' }, { name: 'status', label: t('status'), options: ['PENDING', 'SUBMITTED', 'DELIVERED', 'EXPIRED', 'UNDELIVERABLE', 'REJECTED', 'FAILED'] },
          { name: 'from', label: t('from'), type: 'datetime-local' }, { name: 'to', label: t('to'), type: 'datetime-local' }]}
          onSubmit={(v) => { setF(v); setQ(qs({ ...v, from: v.from ? new Date(v.from).toISOString() : '', to: v.to ? new Date(v.to).toISOString() : '' })); }} />
        <Flash error={error} />
        <Table cols={['createdAt', 'operator', 'msisdn', 'sender', 'status', 'rawStatus', 'segments', 'attempts', 'id']} rows={data} />
      </Card>
    </>
  );
}

export function Ledger() {
  const [status, setStatus] = useState('');
  const [{ data, error }] = useLoad(() => api('/admin/ledger?' + qs({ status })), [status]);
  const [msg, run] = useAction();
  return (
    <Card title={t('ledger')}>
      <label>{t('status')} <select value={status} onChange={(e) => setStatus(e.target.value)}><option value="" />{['PENDING', 'ACCEPTED', 'CHARGED', 'REJECTED', 'REVERSED', 'DISPUTED'].map((s) => <option key={s}>{s}</option>)}</select></label>{' '}
      {['csv', 'xlsx', 'pdf'].map((f) => <button key={f} className="btn sm" onClick={() => run(() => download(`/admin/ledger/export?${qs({ format: f, status })}`, `ledger.${f}`))}>{t('export')} {f.toUpperCase()}</button>)}
      <Flash error={error} {...msg} />
      <Table cols={['eventId', 'type', 'operator', 'service', 'msisdn', 'gross', 'operatorShare', 'partnerShare', 'providerShare', 'taxes', 'status', 'createdAt']} rows={data} />
    </Card>
  );
}

export function Reconciliation() {
  const [ops] = useLoad(() => api('/admin/operators'));
  const [batch, setBatch] = useState(null);
  const [summary, setSummary] = useState(null);
  const [{ data, error }, reload] = useLoad(() => (batch ? api('/admin/reconciliation/' + batch) : Promise.resolve([])), [batch]);
  const [msg, run] = useAction(reload);
  const upload = async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const op = fd.get('operator');
    fd.delete('operator');
    ['from', 'to'].forEach((k) => fd.set(k, new Date(fd.get(k)).toISOString()));
    run(async () => { const r = await api('/admin/reconciliation/' + op, { method: 'POST', form: fd }); setBatch(r.batch); setSummary(r.summary); });
  };
  return (
    <>
      <Card title={t('reconciliation')}>
        <form className="form" onSubmit={upload}>
          <label>{t('operators')}<select name="operator" required>{(ops.data || []).map((o) => <option key={o.id}>{o.code}</option>)}</select></label>
          <label>{t('file')}<input type="file" name="file" accept=".csv,.xlsx" required /></label>
          <label>{t('from')}<input type="datetime-local" name="from" required /></label>
          <label>{t('to')}<input type="datetime-local" name="to" required /></label>
          <label>Séparateur CSV<input name="separator" defaultValue=";" maxLength="1" /></label>
          <label>Col. ID<input name="idColumn" defaultValue="event_id" /></label>
          <label>Col. montant<input name="amountColumn" defaultValue="amount" /></label>
          <label>Col. statut<input name="statusColumn" defaultValue="status" /></label>
          <button className="btn">{t('create')}</button>
        </form>
        <Flash {...msg} />
      </Card>
      {summary && <div className="stats">{Object.entries(summary).map(([k, v]) => <Stat key={k} label={k} value={v} />)}</div>}
      {batch && (
        <Card title={`Lot ${batch}`}>
          {['csv', 'xlsx', 'pdf'].map((f) => <button key={f} className="btn sm" onClick={() => run(() => download(`/admin/reconciliation/${batch}/export?format=${f}`, `ecarts-${batch}.${f}`))}>{t('export')} {f.toUpperCase()}</button>)}
          <Flash error={error} />
          <Table cols={['id', 'eventId', 'operatorAmount', 'platformAmount', 'result', 'comment', t('actions')]} rows={data} render={{
            [t('actions')]: (i) => i.result !== 'MATCHED' && <button className="btn sm" onClick={() => { const c = prompt('Commentaire de correction'); if (c) run(() => api('/admin/reconciliation/items/' + i.id, { method: 'PATCH', body: { comment: c } })); }}>✎</button> }} />
        </Card>
      )}
    </>
  );
}

export function Rules() {
  const [{ data, error }, reload] = useLoad(() => api('/admin/rules'));
  const [sv] = useLoad(() => api('/admin/services'));
  const [msg, run] = useAction(reload);
  return (
    <>
      <Card title={t('rules')}><Flash error={error} {...msg} />
        <Table cols={['type', 'msisdn', 'service', 'reason', t('actions')]} rows={data} render={{ [t('actions')]: (r) => <button className="btn sm" onClick={() => run(() => api('/admin/rules/' + r.id, { method: 'DELETE' }))}>{t('delete')}</button> }} />
      </Card>
      <Card title={t('create')}>
        <Form fields={[{ name: 'type', label: 'Type', required: true, options: ['BLACK', 'WHITE'] }, { name: 'msisdn', label: t('msisdn'), required: true },
          { name: 'serviceId', label: t('services') + ' (global si vide)', options: (sv.data || []).map((s) => ({ value: s.id, label: s.name })) }, { name: 'reason', label: 'Motif' }]}
          onSubmit={(v, done) => run(() => api('/admin/rules', { method: 'POST', body: { ...v, serviceId: v.serviceId ? +v.serviceId : null } }), done)} />
      </Card>
    </>
  );
}

export function Support() {
  const [sv] = useLoad(() => api('/admin/services'));
  const [rows, setRows] = useState(null);
  const [last, setLast] = useState(null);
  const [msg, run] = useAction();
  return (
    <Card title={t('support')}>
      <Form submit={t('search')} fields={[{ name: 'msisdn', label: t('msisdn'), required: true }, { name: 'serviceId', label: t('services'), required: true, options: (sv.data || []).map((s) => ({ value: s.id, label: s.name })) }]}
        onSubmit={(v) => run(async () => { setLast(v); setRows(await api('/admin/consents?' + qs(v))); })} />
      <Flash {...msg} />
      {rows && (
        <>
          <Table cols={['at', 'action', 'channel', 'proof', 'termsVersion']} rows={rows} />
          <button className="btn sm" onClick={() => run(() => download('/admin/consents/export?' + qs(last), 'consentements.csv'))}>{t('export')} CSV</button>{' '}
          <button className="btn sm" onClick={() => run(() => api('/admin/subscriptions/stop?' + qs(last), { method: 'POST' }))}>STOP</button>
        </>
      )}
    </Card>
  );
}

export function Users() {
  const [{ data, error }, reload] = useLoad(() => api('/admin/users'));
  const [pa] = useLoad(() => api('/admin/partners'));
  const [msg, run] = useAction(reload);
  const patch = (u, body) => run(() => api('/admin/users/' + u.id, { method: 'PATCH', body }));
  return (
    <>
      <Card title={t('users')}><Flash error={error} {...msg} />
        <Table cols={['id', 'username', 'roles', 'active', 'mfaEnabled', 'partnerId', t('actions')]} rows={data} render={{
          [t('actions')]: (u) => <>
            <button className="btn sm" onClick={() => patch(u, { active: !u.active })}>{u.active ? t('suspend') : t('activate')}</button>{' '}
            <button className="btn sm" onClick={() => patch(u, { resetMfa: true })}>Reset MFA</button>{' '}
            <button className="btn sm" onClick={() => { const p = prompt('Nouveau mot de passe (12+ car.)'); if (p) patch(u, { password: p }); }}>MDP</button></> }} />
      </Card>
      <Card title={t('create')}>
        <Form fields={[{ name: 'username', label: t('username'), required: true }, { name: 'password', label: t('password'), type: 'password', required: true },
          { name: 'role', label: 'Rôle', required: true, options: ['SUPER_ADMIN', 'NOC', 'VAS_MANAGER', 'FINANCE', 'SUPPORT', 'PARTNER', 'AUDITOR'] },
          { name: 'partnerId', label: t('partners'), options: (pa.data || []).map((p) => ({ value: p.id, label: p.name })) }]}
          onSubmit={(v, done) => run(() => api('/admin/users', { method: 'POST', body: { username: v.username, password: v.password, roles: [v.role], partnerId: v.partnerId ? +v.partnerId : null } }), done)} />
      </Card>
    </>
  );
}

export function ApiClients() {
  const [{ data, error }, reload] = useLoad(() => api('/admin/api-clients'));
  const [pa] = useLoad(() => api('/admin/partners'));
  const [key, setKey] = useState(null);
  const [msg, run] = useAction(reload);
  return (
    <>
      <Card title={t('apiClients')}><Flash error={error} {...msg} />
        <Table cols={['id', 'name', 'scopes', 'rateLimitPerMin', 'active', 'partnerId', t('actions')]} rows={data} render={{
          [t('actions')]: (c) => c.active && <button className="btn sm" onClick={() => run(() => api('/admin/api-clients/' + c.id, { method: 'DELETE' }))}>Révoquer</button> }} />
      </Card>
      {key && <Card title="Clé API (affichée une seule fois)"><code>{key}</code></Card>}
      <Card title={t('create')}>
        <Form fields={[{ name: 'name', label: t('name'), required: true }, { name: 'scopes', label: 'Scopes', required: true, options: [
          { value: 'messages:send,messages:read,services:read,reports:read,subscriptions:write', label: 'Tous' }, { value: 'messages:send,messages:read', label: 'Messages' }, { value: 'services:read,reports:read', label: 'Lecture seule' }] },
          { name: 'partnerId', label: t('partners'), options: (pa.data || []).map((p) => ({ value: p.id, label: p.name })) }, { name: 'rateLimitPerMin', label: 'Quota / min', type: 'number' }]}
          onSubmit={(v, done) => run(async () => { const r = await api('/admin/api-clients', { method: 'POST', body: { ...v, partnerId: v.partnerId ? +v.partnerId : null, rateLimitPerMin: v.rateLimitPerMin ? +v.rateLimitPerMin : null } }); setKey(r.apiKey); }, done)} />
      </Card>
    </>
  );
}

export function Audit() {
  const [{ data, error }] = useLoad(() => api('/admin/audit'));
  return <Card title={t('audit')}><Flash error={error} /><Table cols={['at', 'actor', 'action', 'target', 'detail']} rows={data} /></Card>;
}

export function Security({ me, onChanged }) {
  const [setup, setSetup] = useState(null);
  const [msg, run] = useAction();
  return (
    <Card title={t('security')}>
      <p>{me.mfaEnabled ? `✓ ${t('mfaEnabled')}` : '✗ MFA'}</p>
      {!me.mfaEnabled && !setup && <button className="btn" onClick={() => run(async () => setSetup(await api('/admin/me/mfa/setup', { method: 'POST' })))}>{t('mfaSetup')}</button>}
      {setup && <>
        <p>{t('mfaSecret')} :</p><code>{setup.secret}</code><p className="muted">{setup.otpauthUri}</p>
        <Form submit={t('mfaConfirm')} fields={[{ name: 'code', label: t('totp'), required: true }]}
          onSubmit={(v) => run(async () => { await api('/admin/me/mfa/confirm', { method: 'POST', body: { code: v.code } }); onChanged(); })} />
      </>}
      <Flash {...msg} />
    </Card>
  );
}

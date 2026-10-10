import { useState } from 'react';
import { api, download, qs } from './api.js';
import { t } from './i18n.js';
import { useLoad, usePaged, Pager, Flash, Table, Form, Card, Stat, Modal, useConfirm, rules } from './ui.jsx';
import { ServiceEngine } from './engines.jsx';

/** Hook d'action : exécute, affiche succès/erreur, recharge ; `busy` désactive les boutons pendant l'appel. */
export function useAction(reload) {
  const [msg, setMsg] = useState({});
  const [busy, setBusy] = useState(false);
  const run = async (fn, done) => {
    setBusy(true);
    try { await fn(); setMsg({ ok: t('ok') }); done?.(); reload?.(); } catch (e) { setMsg({ error: e.message }); } finally { setBusy(false); }
  };
  return [msg, run, busy];
}

export const pct = (x) => `${(100 * (x || 0)).toFixed(1)} %`;
export const iso = (v) => (v ? new Date(v).toISOString() : undefined);
export const exportButtons = (run, mk, name) => ['csv', 'xlsx', 'pdf'].map((f) => <button key={f} type="button" className="btn sm" onClick={() => run(() => download(mk(f), `${name}.${f}`))}>{t('export')} {f.toUpperCase()}</button>);
export const Btn = ({ children, ...p }) => <button type="button" className="btn sm" {...p}>{children}</button>;

export function Dashboard() {
  const [hours, setHours] = useState(24);
  const [{ data, error, loading }] = useLoad(() => api('/admin/dashboard?hours=' + hours), [hours]);
  const [msg, run] = useAction();
  return (
    <>
      <Card>
        <label>{t('hours')} <select value={hours} onChange={(e) => setHours(+e.target.value)}>{[1, 6, 24, 72, 168].map((h) => <option key={h}>{h}</option>)}</select></label>{' '}
        {exportButtons(run, (f) => `/admin/reports/summary/export?format=${f}&hours=${hours}`, 'synthese')}
        <Flash {...msg} />
      </Card>
      <Flash error={error} />
      {loading && <p className="muted" role="status">{t('loading')}</p>}
      {data && (
        <>
          <div className="stats">
            <Stat label={t('pending')} value={data.pending} />
            <Stat label={t('deliveryRate')} value={pct(data.deliveryRate)} />
            {Object.entries(data.mo).map(([k, v]) => <Stat key={'mo' + k} label={'MO ' + k} value={v} />)}
            {Object.entries(data.mt).map(([k, v]) => <Stat key={'mt' + k} label={'MT ' + k} value={v} />)}
          </div>
          <Card title={t('operators')}><Table caption={t('operators')} cols={['code', 'status', 'maxTps']} rows={data.operators} /></Card>
          {Object.keys(data.billing).length > 0 && <Card title={t('ledger')}><Table caption={t('ledger')} cols={['status', 'gross', 'partner', 'provider', 'events']} labels={{ status: t('status'), gross: t('total'), partner: t('partners'), provider: 'VAS' }} rows={Object.entries(data.billing).map(([k, v]) => ({ id: k, status: k, ...v }))} /></Card>}
        </>
      )}
    </>
  );
}

export function Operators() {
  const [{ rows, total, error }, pg] = usePaged('/admin/operators');
  const [edit, setEdit] = useState(null);
  const [msg, run, busy] = useAction(pg.reload);
  const confirm = useConfirm();
  const patch = (o, body) => run(() => api('/admin/operators/' + o.id, { method: 'PATCH', body }));
  const toggle = async (o) => { if (o.status !== 'ACTIVE' || await confirm(t('confirmSuspend'), { danger: true })) patch(o, { status: o.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' }); };
  return (
    <Card title={t('operators')}>
      <Flash error={error} {...msg} />
      <Table caption={t('operators')} cols={['code', 'name', 'status', 'maxTps', 'dlrBillingRule', 'msisdnPrefixes', 'actions']} labels={{ actions: t('actions') }} rows={rows} render={{
        actions: (o) => (
          <>
            <Btn onClick={() => toggle(o)} disabled={busy}>{o.status === 'ACTIVE' ? t('suspend') : t('activate')}</Btn>{' '}
            <Btn onClick={() => setEdit(o)}>{t('edit')}</Btn>{' '}
            <Btn disabled={busy} onClick={() => patch(o, { dlrBillingRule: o.dlrBillingRule === 'ON_DELIVERED' ? 'ON_SUBMITTED' : 'ON_DELIVERED' })}>DLR</Btn>
          </>) }} />
      {edit && (
        <Modal title={`${t('edit')} — ${edit.code}`} onClose={() => setEdit(null)}>
          <Form submit={t('save')} busy={busy} onCancel={() => setEdit(null)} initial={{ maxTps: edit.maxTps, msisdnPrefixes: edit.msisdnPrefixes }}
            fields={[{ name: 'maxTps', label: t('tps'), type: 'number', min: 1, required: true, validate: rules.positive }, { name: 'msisdnPrefixes', label: t('prefixes') + ' (9,4)', validate: (v) => (/^(\d{1,3})(,\d{1,3})*$/.test(v) ? null : t('invalidValue')) }]}
            onSubmit={(v) => run(() => api('/admin/operators/' + edit.id, { method: 'PATCH', body: { maxTps: +v.maxTps, msisdnPrefixes: v.msisdnPrefixes ?? '' } }), () => setEdit(null))} />
        </Modal>)}
      <Pager total={total} {...pg} />
    </Card>
  );
}

export function ShortCodes() {
  const [{ rows, total, error }, pg] = usePaged('/admin/shortcodes');
  const [ops] = useLoad(() => api('/admin/operators?size=100').then((x) => x));
  const [msg, run, busy] = useAction(pg.reload);
  const confirm = useConfirm();
  const flip = async (s) => { if (s.status !== 'ACTIVE' || await confirm(t('confirmSuspend'), { danger: true })) run(() => api(`/admin/shortcodes/${s.id}/${s.status === 'ACTIVE' ? 'suspend' : 'activate'}`, { method: 'POST' })); };
  return (
    <>
      <Card title={t('shortcodes')}>
        <Flash error={error} {...msg} />
        <Table caption={t('shortcodes')} cols={['number', 'operator', 'status', 'actions']} labels={{ actions: t('actions') }} rows={rows.map((s) => ({ ...s, operator: s.operator?.code }))} render={{
          actions: (s) => <Btn disabled={busy} onClick={() => flip(s)}>{s.status === 'ACTIVE' ? t('suspend') : t('activate')}</Btn> }} />
        <Pager total={total} {...pg} />
      </Card>
      <Card title={t('create')}>
        <Form busy={busy} fields={[{ name: 'number', label: 'Short code', required: true, validate: (v) => (/^\d{3,8}$/.test(v) ? null : t('invalidValue')) }, { name: 'operatorCode', label: t('operators'), required: true, options: (ops.data || []).map((o) => o.code) }]}
          onSubmit={(v, done) => run(() => api('/admin/shortcodes', { method: 'POST', body: v }), done)} />
      </Card>
    </>
  );
}

export function Partners() {
  const [{ rows, total, error }, pg] = usePaged('/admin/partners');
  const [msg, run, busy] = useAction(pg.reload);
  return (
    <>
      <Card title={t('partners')}><Flash error={error} {...msg} /><Table caption={t('partners')} cols={['id', 'name', 'sharePercent', 'maxTps', 'webhookUrl']} rows={rows} /><Pager total={total} {...pg} /></Card>
      <Card title={t('create')}>
        <Form busy={busy} fields={[{ name: 'name', label: t('name'), required: true }, { name: 'sharePercent', label: '% partenaire', type: 'number', step: '0.01', validate: rules.percent }, { name: 'maxTps', label: t('tps'), type: 'number', min: 0 },
          { name: 'webhookUrl', label: 'Webhook URL (https)', validate: (v) => (/^https?:\/\/[^\s]+$/.test(v) ? null : t('invalidValue')) }, { name: 'webhookSecret', label: 'Webhook secret', type: 'password', validate: rules.minLength(16) }]}
          onSubmit={(v, done) => run(() => api('/admin/partners', { method: 'POST', body: { ...v, sharePercent: v.sharePercent ? +v.sharePercent : 0, maxTps: v.maxTps ? +v.maxTps : 0 } }), done)} />
      </Card>
    </>
  );
}

const serviceFields = (sc, pa, creating) => [
  { name: 'name', label: t('name'), required: true },
  creating && { name: 'type', label: 'Type', required: true, options: ['VOTE', 'QUIZ', 'PREMIUM_CONTENT', 'SUBSCRIPTION', 'ALERT'] },
  creating && { name: 'shortCodeId', label: 'Short code', required: true, options: (sc || []).map((s) => ({ value: s.id, label: `${s.number} (${s.operator?.code})` })) },
  { name: 'partnerId', label: t('partners'), options: (pa || []).map((p) => ({ value: p.id, label: p.name })) },
  { name: 'consentMode', label: 'Consentement', options: ['SIMPLE_OPT_IN', 'DOUBLE_OPT_IN', 'API_ACTIVATION'] },
  { name: 'defaultLang', label: t('language'), options: ['fr', 'ar', 'en'] },
  { name: 'maxActionsPerMsisdn', label: 'Max actions / MSISDN', type: 'number', min: 0 }, { name: 'maxTps', label: t('tps'), type: 'number', min: 0 },
  { name: 'opensAt', label: 'Ouverture', type: 'datetime-local' }, { name: 'closesAt', label: 'Fermeture', type: 'datetime-local' },
  creating && { name: 'regulated', label: 'Réglementé', type: 'checkbox' },
].filter(Boolean);
const serviceBody = (v) => ({ ...v, shortCodeId: v.shortCodeId ? +v.shortCodeId : undefined, partnerId: v.partnerId ? +v.partnerId : undefined, maxActionsPerMsisdn: v.maxActionsPerMsisdn !== '' && v.maxActionsPerMsisdn != null ? +v.maxActionsPerMsisdn : undefined, maxTps: v.maxTps !== '' && v.maxTps != null ? +v.maxTps : undefined, opensAt: iso(v.opensAt), closesAt: iso(v.closesAt) });
const local = (iso) => (iso ? new Date(iso).toISOString().slice(0, 16) : '');

export function Services() {
  const [{ rows, total, error }, pg] = usePaged('/admin/services');
  const [sc] = useLoad(() => api('/admin/shortcodes?size=500'));
  const [pa] = useLoad(() => api('/admin/partners?size=500'));
  const [sel, setSel] = useState(null);
  const [edit, setEdit] = useState(null);
  const [msg, run, busy] = useAction(pg.reload);
  const confirm = useConfirm();
  const st = async (s, status) => { if (status !== 'SUSPENDED' || await confirm(t('confirmSuspend'), { danger: true })) run(() => api(`/admin/services/${s.id}/status/${status}`, { method: 'POST' })); };
  const approve = async (s) => { if (await confirm(t('confirmRegulatory'))) run(() => api(`/admin/services/${s.id}/regulatory-approval`, { method: 'POST' })); };
  return (
    <>
      <Card title={t('services')}>
        <Flash error={error} {...msg} />
        <Table caption={t('services')} cols={['id', 'name', 'type', 'status', 'shortCode', 'operator', 'partner', 'maxTps', 'regulated', 'regulatoryApproved', 'actions']} labels={{ actions: t('actions') }} rows={rows} render={{
          actions: (s) => (
            <>
              {s.status !== 'ACTIVE' && <Btn disabled={busy} onClick={() => st(s, 'ACTIVE')}>{t('activate')}</Btn>}{' '}
              {s.status === 'ACTIVE' && <Btn disabled={busy} onClick={() => st(s, 'SUSPENDED')}>{t('suspend')}</Btn>}{' '}
              {s.regulated && !s.regulatoryApproved && <Btn disabled={busy} onClick={() => approve(s)}>{t('regulatory')}</Btn>}{' '}
              <Btn onClick={() => setEdit(s)}>{t('edit')}</Btn>{' '}
              <Btn onClick={() => setSel(s)}>{t('keywords')} / {t('replies')}</Btn>
            </>) }} />
        <Pager total={total} {...pg} />
      </Card>
      {edit && (
        <Modal title={`${t('edit')} — ${edit.name}`} onClose={() => setEdit(null)}>
          <Form submit={t('save')} busy={busy} onCancel={() => setEdit(null)} fields={serviceFields(sc.data, pa.data, false)}
            initial={{ name: edit.name, consentMode: edit.consentMode, defaultLang: edit.defaultLang, maxActionsPerMsisdn: edit.maxActionsPerMsisdn, maxTps: edit.maxTps, opensAt: local(edit.opensAt), closesAt: local(edit.closesAt) }}
            onSubmit={(v) => run(() => api('/admin/services/' + edit.id, { method: 'PATCH', body: serviceBody(v) }), () => setEdit(null))} />
        </Modal>)}
      {sel && <ServiceDetail service={sel} onClose={() => setSel(null)} />}
      <Card title={t('create')}>
        <Form busy={busy} fields={serviceFields(sc.data, pa.data, true)} onSubmit={(v, done) => run(() => api('/admin/services', { method: 'POST', body: serviceBody(v) }), done)} />
      </Card>
    </>
  );
}

function ServiceDetail({ service, onClose }) {
  const [kw, reloadKw] = useLoad(() => api('/admin/keywords?serviceId=' + service.id), [service.id]);
  const [rp, reloadRp] = useLoad(() => api('/admin/replies?serviceId=' + service.id), [service.id]);
  const [msg, run, busy] = useAction();
  const confirm = useConfirm();
  const del = async (k) => { if (await confirm(t('confirmDelete'), { danger: true })) run(() => api('/admin/keywords/' + k.id, { method: 'DELETE' }), reloadKw); };
  return (
    <Modal title={`${service.name} — ${t('keywords')} / ${t('replies')}`} onClose={onClose}>
      <Flash {...msg} />
      <Table caption={t('keywords')} cols={['word', 'actions']} labels={{ actions: t('actions') }} rows={kw.data} render={{ actions: (k) => <Btn disabled={busy} onClick={() => del(k)}>{t('delete')}</Btn> }} />
      <Form busy={busy} fields={[{ name: 'word', label: t('keywords'), required: true, validate: (v) => (/^\S{1,40}$/.test(v) ? null : t('invalidValue')) }]} onSubmit={(v, done) => run(() => api('/admin/keywords', { method: 'POST', body: { serviceId: service.id, word: v.word } }), () => { done(); reloadKw(); })} />
      <h4>{t('replies')}</h4>
      <Table caption={t('replies')} cols={['lang', 'kind', 'text']} rows={rp.data} />
      <Form submit={t('save')} busy={busy} fields={[
        { name: 'lang', label: t('language'), required: true, options: ['fr', 'ar', 'en'] },
        { name: 'kind', label: 'Type', required: true, options: ['OK', 'STOP', 'HELP', 'LIMIT', 'CLOSED', 'CONFIRM', 'ALREADY', 'SUB_OK', 'RENEWAL'] },
        { name: 'text', label: t('comment'), required: true }]}
        onSubmit={(v, done) => run(() => api('/admin/replies', { method: 'PUT', body: { serviceId: service.id, ...v } }), () => { done(); reloadRp(); })} />
      <ServiceEngine service={service} />
      <div className="actions"><button type="button" className="btn ghost" onClick={onClose}>{t('close')}</button></div>
    </Modal>
  );
}

const tariffFields = (services, creating) => [
  creating && { name: 'serviceId', label: t('services'), required: true, options: (services || []).map((s) => ({ value: s.id, label: s.name })) },
  creating && { name: 'eventType', label: 'Événement', required: true, options: ['MT', 'SUBSCRIPTION', 'RENEWAL', 'MO'] },
  { name: 'grossAmount', label: 'Prix facial TTC', type: 'number', step: '0.001', required: true, validate: rules.positive },
  { name: 'operatorPercent', label: '% opérateur', type: 'number', step: '0.01', required: true, validate: rules.percent },
  { name: 'taxPercent', label: '% taxes', type: 'number', step: '0.01', validate: rules.percent },
  { name: 'effectiveFrom', label: 'Effet', type: 'datetime-local' },
].filter(Boolean);
const tariffBody = (v) => ({ ...v, serviceId: v.serviceId ? +v.serviceId : undefined, grossAmount: +v.grossAmount, operatorPercent: +v.operatorPercent, taxPercent: v.taxPercent ? +v.taxPercent : 0, effectiveFrom: iso(v.effectiveFrom) });

export function Tariffs() {
  const [{ rows, total, error }, pg] = usePaged('/admin/tariffs');
  const [sv] = useLoad(() => api('/admin/services?size=500'));
  const [sim, setSim] = useState(null);
  const [edit, setEdit] = useState(null); // { tariff, mode: 'edit' | 'version' }
  const [msg, run, busy] = useAction(pg.reload);
  const confirm = useConfirm();
  const approve = async (x) => { if (await confirm(t('confirmApprove'))) run(() => api(`/admin/tariffs/${x.id}/approve`, { method: 'POST' })); };
  const del = async (x) => { if (await confirm(t('confirmDelete'), { danger: true })) run(() => api('/admin/tariffs/' + x.id, { method: 'DELETE' })); };
  return (
    <>
      <Card title={t('tariffs')}>
        <Flash error={error} {...msg} />
        <Table caption={t('tariffs')} cols={['id', 'service', 'eventType', 'grossAmount', 'operatorPercent', 'taxPercent', 'effectiveFrom', 'approved', 'createdBy', 'actions']} labels={{ actions: t('actions') }} rows={rows} render={{
          actions: (x) => x.approved
            ? <Btn onClick={() => setEdit({ tariff: x, mode: 'version' })}>{t('newVersion')}</Btn>
            : <><Btn disabled={busy} onClick={() => approve(x)}>{t('approve')}</Btn>{' '}<Btn onClick={() => setEdit({ tariff: x, mode: 'edit' })}>{t('edit')}</Btn>{' '}<Btn disabled={busy} onClick={() => del(x)}>{t('delete')}</Btn></> }} />
        <Pager total={total} {...pg} />
      </Card>
      {edit && (
        <Modal title={`${edit.mode === 'edit' ? t('edit') : t('newVersion')} — ${edit.tariff.service} (${edit.tariff.eventType})`} onClose={() => setEdit(null)}>
          <Form submit={t('save')} busy={busy} onCancel={() => setEdit(null)} fields={tariffFields(sv.data, false)}
            initial={{ grossAmount: edit.tariff.grossAmount, operatorPercent: edit.tariff.operatorPercent, taxPercent: edit.tariff.taxPercent }}
            onSubmit={(v) => run(() => edit.mode === 'edit'
              ? api('/admin/tariffs/' + edit.tariff.id, { method: 'PATCH', body: tariffBody(v) })
              : api('/admin/tariffs', { method: 'POST', body: { ...tariffBody(v), serviceId: edit.tariff.serviceId, eventType: edit.tariff.eventType } }), () => setEdit(null))} />
        </Modal>)}
      <Card title={t('create')}>
        <Form busy={busy} fields={tariffFields(sv.data, true)} onSubmit={(v, done) => run(() => api('/admin/tariffs', { method: 'POST', body: tariffBody(v) }), done)} />
      </Card>
      <Card title={t('simulate')}>
        <Form submit={t('simulate')} fields={[{ name: 'gross', label: 'Prix TTC', type: 'number', step: '0.001', required: true, validate: rules.positive }, { name: 'tax', label: '% taxes', type: 'number', required: true, validate: rules.percent },
          { name: 'operatorPercent', label: '% opérateur', type: 'number', required: true, validate: rules.percent }, { name: 'partnerPercent', label: '% partenaire', type: 'number', validate: rules.percent }]}
          onSubmit={async (v) => setSim(await api('/admin/tariffs/simulate?' + qs({ ...v, partnerPercent: v.partnerPercent || 0 })))} />
        {sim && <Table caption={t('simulate')} cols={['gross', 'taxes', 'operatorShare', 'partnerShare', 'providerShare']} rows={[{ id: 1, ...sim }]} />}
      </Card>
    </>
  );
}

export function Messages() {
  const [filters, setFilters] = useState({});
  const [{ rows, total, error }, pg] = usePaged('/admin/messages', filters);
  return (
    <Card title={t('messages')}>
      <Form submit={t('search')} fields={[{ name: 'id', label: 'ID' }, { name: 'msisdn', label: t('msisdn'), validate: rules.msisdn }, { name: 'operator', label: t('operators'), options: ['TT', 'ORANGE', 'OOREDOO'] },
        { name: 'shortCode', label: 'Short code' }, { name: 'status', label: t('status'), options: ['PENDING', 'SUBMITTED', 'DELIVERED', 'EXPIRED', 'UNDELIVERABLE', 'REJECTED', 'FAILED', 'UNKNOWN'] },
        { name: 'from', label: t('from'), type: 'datetime-local' }, { name: 'to', label: t('to'), type: 'datetime-local' }]}
        onSubmit={(v) => setFilters({ ...v, from: iso(v.from) ?? '', to: iso(v.to) ?? '' })} />
      <Flash error={error} />
      <Table caption={t('messages')} cols={['createdAt', 'operator', 'msisdn', 'sender', 'status', 'rawStatus', 'segments', 'attempts', 'id']} rows={rows} />
      <Pager total={total} {...pg} />
    </Card>
  );
}

export function Ledger() {
  const [f, setF] = useState({ status: '', from: '', to: '' });
  const params = { status: f.status, from: iso(f.from) ?? '', to: iso(f.to) ?? '' };
  const [{ rows, total, error }, pg] = usePaged('/admin/ledger', params);
  const [msg, run] = useAction();
  return (
    <Card title={t('ledger')}>
      <div className="form">
        <div className="field"><label htmlFor="lst">{t('status')}</label><select id="lst" value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}><option value="" />{['PENDING', 'ACCEPTED', 'CHARGED', 'REJECTED', 'REVERSED', 'DISPUTED'].map((s) => <option key={s}>{s}</option>)}</select></div>
        <div className="field"><label htmlFor="lfr">{t('from')}</label><input id="lfr" type="datetime-local" value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} /></div>
        <div className="field"><label htmlFor="lto">{t('to')}</label><input id="lto" type="datetime-local" value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} /></div>
        <div className="actions">{exportButtons(run, (fm) => `/admin/ledger/export?${qs({ format: fm, ...params })}`, 'ledger')}</div>
      </div>
      <Flash error={error} {...msg} />
      <Table caption={t('ledger')} cols={['eventId', 'type', 'operator', 'service', 'msisdn', 'gross', 'operatorShare', 'partnerShare', 'providerShare', 'taxes', 'status', 'createdAt']} labels={{ type: 'Type' }} rows={rows} />
      <Pager total={total} {...pg} />
    </Card>
  );
}

export function Reconciliation() {
  const [ops] = useLoad(() => api('/admin/operators?size=100'));
  const [batch, setBatch] = useState(null);
  const [summary, setSummary] = useState(null);
  const [{ rows, total, error }, pg] = usePaged(batch ? '/admin/reconciliation/' + batch : null, {}, [batch]);
  const [msg, run, busy] = useAction(pg.reload);
  const [fix, setFix] = useState(null);
  const upload = async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target);
    const op = fd.get('operator');
    fd.delete('operator');
    ['from', 'to'].forEach((k) => fd.set(k, new Date(fd.get(k)).toISOString()));
    run(async () => { const r = await api('/admin/reconciliation/' + op, { method: 'POST', form: fd }); setBatch(r.batch); setSummary(r.summary); });
  };
  const lab = (id, text, node) => <div className="field"><label htmlFor={id}>{text}</label>{node}</div>;
  return (
    <>
      <Card title={t('reconciliation')}>
        <form className="form" onSubmit={upload}>
          {lab('rc-op', t('operators'), <select id="rc-op" name="operator" required>{(ops.data || []).map((o) => <option key={o.id}>{o.code}</option>)}</select>)}
          {lab('rc-file', t('file'), <input id="rc-file" type="file" name="file" accept=".csv,.xlsx" required />)}
          {lab('rc-from', t('from'), <input id="rc-from" type="datetime-local" name="from" required />)}
          {lab('rc-to', t('to'), <input id="rc-to" type="datetime-local" name="to" required />)}
          {lab('rc-sep', 'CSV', <input id="rc-sep" name="separator" defaultValue=";" maxLength="1" />)}
          {lab('rc-id', 'ID', <input id="rc-id" name="idColumn" defaultValue="event_id" />)}
          {lab('rc-am', t('total') + ' (colonne)', <input id="rc-am" name="amountColumn" defaultValue="amount" />)}
          {lab('rc-st', t('status'), <input id="rc-st" name="statusColumn" defaultValue="status" />)}
          <div className="actions"><button className="btn" disabled={busy}>{busy ? t('saving') : t('create')}</button></div>
        </form>
        <Flash {...msg} />
      </Card>
      {summary && <div className="stats">{Object.entries(summary).map(([k, v]) => <Stat key={k} label={k} value={v} />)}</div>}
      {batch && (
        <Card title={`Lot ${batch}`}>
          <div className="actions">{exportButtons(run, (fm) => `/admin/reconciliation/${batch}/export?format=${fm}`, `ecarts-${batch}`)}</div>
          <Flash error={error} />
          <Table caption={`Lot ${batch}`} cols={['id', 'eventId', 'operatorAmount', 'platformAmount', 'result', 'comment', 'actions']} labels={{ actions: t('actions') }} rows={rows} render={{
            actions: (i) => i.result !== 'MATCHED' && <Btn onClick={() => setFix(i)}>{t('edit')}</Btn> }} />
          <Pager total={total} {...pg} />
        </Card>
      )}
      {fix && (
        <Modal title={`${t('comment')} — ${fix.eventId ?? fix.id}`} onClose={() => setFix(null)}>
          <Form submit={t('save')} busy={busy} onCancel={() => setFix(null)} fields={[{ name: 'comment', label: t('comment'), required: true }, { name: 'newStatus', label: t('status') + ' (ledger)', options: ['CHARGED', 'REJECTED', 'REVERSED', 'DISPUTED'] }]}
            onSubmit={(v) => run(() => api('/admin/reconciliation/items/' + fix.id, { method: 'PATCH', body: { comment: v.comment, newStatus: v.newStatus || null } }), () => setFix(null))} />
        </Modal>)}
    </>
  );
}


export function Rules() {
  const [{ rows, total, error }, pg] = usePaged('/admin/rules');
  const [sv] = useLoad(() => api('/admin/services?size=500'));
  const [msg, run, busy] = useAction(pg.reload);
  const confirm = useConfirm();
  const del = async (r) => { if (await confirm(t('confirmDelete'), { danger: true })) run(() => api('/admin/rules/' + r.id, { method: 'DELETE' })); };
  return (
    <>
      <Card title={t('rules')}><Flash error={error} {...msg} />
        <Table caption={t('rules')} cols={['type', 'msisdn', 'service', 'reason', 'actions']} labels={{ actions: t('actions') }} rows={rows} render={{ actions: (r) => <Btn disabled={busy} onClick={() => del(r)}>{t('delete')}</Btn> }} />
        <Pager total={total} {...pg} />
      </Card>
      <Card title={t('create')}>
        <Form busy={busy} fields={[{ name: 'type', label: 'Type', required: true, options: ['BLACK', 'WHITE'] }, { name: 'msisdn', label: t('msisdn'), required: true, validate: rules.msisdn },
          { name: 'serviceId', label: t('services'), options: (sv.data || []).map((s) => ({ value: s.id, label: s.name })) }, { name: 'reason', label: t('comment') }]}
          onSubmit={(v, done) => run(() => api('/admin/rules', { method: 'POST', body: { ...v, serviceId: v.serviceId ? +v.serviceId : null } }), done)} />
      </Card>
    </>
  );
}

export function Support() {
  const [sv] = useLoad(() => api('/admin/services?size=500'));
  const [rows, setRows] = useState(null);
  const [last, setLast] = useState(null);
  const [msg, run, busy] = useAction();
  const confirm = useConfirm();
  const stop = async () => { if (await confirm(t('confirmStop'), { danger: true })) run(() => api('/admin/subscriptions/stop?' + qs(last), { method: 'POST' })); };
  return (
    <Card title={t('support')}>
      <Form submit={t('search')} busy={busy} fields={[{ name: 'msisdn', label: t('msisdn'), required: true, validate: rules.msisdn }, { name: 'serviceId', label: t('services'), required: true, options: (sv.data || []).map((s) => ({ value: s.id, label: s.name })) }]}
        onSubmit={(v) => run(async () => { setLast(v); setRows(await api('/admin/consents?' + qs(v))); })} />
      <Flash {...msg} />
      {rows && (
        <>
          <Table caption={t('support')} cols={['at', 'action', 'channel', 'proof', 'termsVersion']} rows={rows.map((r, i) => ({ id: i, ...r }))} />
          <div className="actions">
            <Btn onClick={() => run(() => download('/admin/consents/export?' + qs(last), 'consentements.csv'))}>{t('export')} CSV</Btn>{' '}
            <Btn disabled={busy} onClick={stop}>STOP</Btn>
          </div>
        </>
      )}
    </Card>
  );
}

export function Users() {
  const [{ rows, total, error }, pg] = usePaged('/admin/users');
  const [pa] = useLoad(() => api('/admin/partners?size=500'));
  const [msg, run, busy] = useAction(pg.reload);
  const [pwd, setPwd] = useState(null);
  const confirm = useConfirm();
  const patch = (u, body) => run(() => api('/admin/users/' + u.id, { method: 'PATCH', body }));
  const toggle = async (u) => { if (!u.active || await confirm(t('confirmSuspend'), { danger: true })) patch(u, { active: !u.active }); };
  return (
    <>
      <Card title={t('users')}><Flash error={error} {...msg} />
        <Table caption={t('users')} cols={['id', 'username', 'roles', 'active', 'mfaEnabled', 'partnerId', 'actions']} labels={{ actions: t('actions') }} rows={rows} render={{
          actions: (u) => <>
            <Btn disabled={busy} onClick={() => toggle(u)}>{u.active ? t('suspend') : t('activate')}</Btn>{' '}
            <Btn disabled={busy} onClick={async () => { if (await confirm('Reset MFA ?', { danger: true })) patch(u, { resetMfa: true }); }}>Reset MFA</Btn>{' '}
            <Btn onClick={() => setPwd(u)}>{t('password')}</Btn></> }} />
        <Pager total={total} {...pg} />
      </Card>
      {pwd && (
        <Modal title={`${t('password')} — ${pwd.username}`} onClose={() => setPwd(null)}>
          <Form submit={t('save')} busy={busy} onCancel={() => setPwd(null)} fields={[{ name: 'password', label: t('newPassword'), type: 'password', required: true, autoComplete: 'new-password', validate: (v) => (v.length >= 12 && /\d/.test(v) && /[A-Za-z]/.test(v) ? null : t('minLength').replace('{n}', 12)) }]}
            onSubmit={(v) => run(() => api('/admin/users/' + pwd.id, { method: 'PATCH', body: { password: v.password } }), () => setPwd(null))} />
        </Modal>)}
      <Card title={t('create')}>
        <Form busy={busy} fields={[{ name: 'username', label: t('username'), required: true, validate: (v) => (/^[A-Za-z0-9._-]{3,40}$/.test(v) ? null : t('invalidValue')) },
          { name: 'password', label: t('password'), type: 'password', required: true, autoComplete: 'new-password', validate: (v) => (v.length >= 12 && /\d/.test(v) && /[A-Za-z]/.test(v) ? null : t('minLength').replace('{n}', 12)) },
          { name: 'role', label: 'Rôle', required: true, options: ['SUPER_ADMIN', 'NOC', 'VAS_MANAGER', 'FINANCE', 'SUPPORT', 'PARTNER', 'AUDITOR'] },
          { name: 'partnerId', label: t('partners'), options: (pa.data || []).map((p) => ({ value: p.id, label: p.name })) }]}
          onSubmit={(v, done) => run(() => api('/admin/users', { method: 'POST', body: { username: v.username, password: v.password, roles: [v.role], partnerId: v.partnerId ? +v.partnerId : null } }), done)} />
      </Card>
    </>
  );
}

export function ApiClients() {
  const [{ rows, total, error }, pg] = usePaged('/admin/api-clients');
  const [pa] = useLoad(() => api('/admin/partners?size=500'));
  const [key, setKey] = useState(null);
  const [msg, run, busy] = useAction(pg.reload);
  const confirm = useConfirm();
  const revoke = async (c) => { if (await confirm(t('confirmRevoke'), { danger: true })) run(() => api('/admin/api-clients/' + c.id, { method: 'DELETE' })); };
  return (
    <>
      <Card title={t('apiClients')}><Flash error={error} {...msg} />
        <Table caption={t('apiClients')} cols={['id', 'name', 'scopes', 'rateLimitPerMin', 'active', 'partnerId', 'actions']} labels={{ actions: t('actions') }} rows={rows} render={{
          actions: (c) => c.active && <Btn disabled={busy} onClick={() => revoke(c)}>{t('delete')}</Btn> }} />
        <Pager total={total} {...pg} />
      </Card>
      {key && <Card title="Clé API (affichée une seule fois)"><code>{key}</code></Card>}
      <Card title={t('create')}>
        <Form busy={busy} fields={[{ name: 'name', label: t('name'), required: true }, { name: 'scopes', label: 'Scopes', required: true, options: [
          { value: 'messages:send,messages:read,services:read,reports:read,subscriptions:write', label: 'Tous' }, { value: 'messages:send,messages:read', label: 'Messages' }, { value: 'services:read,reports:read', label: 'Lecture seule' }] },
          { name: 'partnerId', label: t('partners'), options: (pa.data || []).map((p) => ({ value: p.id, label: p.name })) }, { name: 'rateLimitPerMin', label: 'Quota / min', type: 'number', min: 1 }]}
          onSubmit={(v, done) => run(async () => { const r = await api('/admin/api-clients', { method: 'POST', body: { ...v, partnerId: v.partnerId ? +v.partnerId : null, rateLimitPerMin: v.rateLimitPerMin ? +v.rateLimitPerMin : null } }); setKey(r.apiKey); }, done)} />
      </Card>
    </>
  );
}

export function Audit() {
  const [{ rows, total, error }, pg] = usePaged('/admin/audit');
  return <Card title={t('audit')}><Flash error={error} /><Table caption={t('audit')} cols={['at', 'actor', 'action', 'target', 'detail']} rows={rows} /><Pager total={total} {...pg} /></Card>;
}

/** Changement de son propre mot de passe : toutes les sessions sont ensuite révoquées (reconnexion). */
export function ChangePassword({ onDone, forced }) {
  const [msg, run, busy] = useAction();
  const strong = (v) => (v.length >= 12 && /\d/.test(v) && /[A-Za-z]/.test(v) ? null : t('minLength').replace('{n}', 12));
  return (
    <Card title={t('changePassword')}>
      {forced && <p role="alert">{t('mustChange')}</p>}
      <Form submit={t('save')} busy={busy} fields={[
        { name: 'current', label: t('currentPassword'), type: 'password', required: true, autoComplete: 'current-password' },
        { name: 'newPassword', label: t('newPassword'), type: 'password', required: true, autoComplete: 'new-password', validate: strong },
        { name: 'confirm', label: t('confirmPassword'), type: 'password', required: true, autoComplete: 'new-password' }]}
        onSubmit={(v) => (v.newPassword !== v.confirm ? run(async () => { throw new Error(t('passwordMismatch')); }) : run(async () => { await api('/admin/me/password', { method: 'POST', body: { current: v.current, newPassword: v.newPassword } }); onDone(); }))} />
      <Flash {...msg} />
    </Card>
  );
}

export function Security({ me, onChanged }) {
  const [setup, setSetup] = useState(null);
  const [msg, run, busy] = useAction();
  const confirm = useConfirm();
  return (
    <>
    <Card title={t('security')}>
      <p>{me.mfaEnabled ? `✓ ${t('mfaEnabled')}` : '✗ MFA'}</p>
      {!me.mfaEnabled && !setup && <button type="button" className="btn" disabled={busy} onClick={() => run(async () => setSetup(await api('/admin/me/mfa/setup', { method: 'POST' })))}>{t('mfaSetup')}</button>}
      {setup && <>
        <p>{t('mfaSecret')} :</p><code>{setup.secret}</code><p className="muted">{setup.otpauthUri}</p>
        <Form submit={t('mfaConfirm')} busy={busy} fields={[{ name: 'code', label: t('totp'), required: true, validate: (v) => (/^\d{6}$/.test(v) ? null : t('invalidValue')), autoComplete: 'one-time-code' }]}
          onSubmit={(v) => run(async () => { await api('/admin/me/mfa/confirm', { method: 'POST', body: { code: v.code } }); onChanged(); })} />
      </>}
      <Flash {...msg} />
    </Card>
    <ChangePassword onDone={() => onChanged('passwordReconnect')} />
    <Card title={t('sessions')}>
      <button type="button" className="btn" disabled={busy} onClick={async () => { if (await confirm(t('confirmLogoutAll'), { danger: true })) run(async () => { await api('/admin/me/logout-all', { method: 'POST' }); onChanged('sessionsRevoked'); }); }}>{t('logoutAll')}</button>
    </Card>
    </>
  );
}

import { useEffect, useState } from 'react';
import { api, download, qs } from './api.js';
import { t, tc } from './i18n.js';
import { useLoad, Flash, Table, Form, Card, Stat, useConfirm } from './ui.jsx';
import { useAction, Btn, pct } from './pages.jsx';

const tnd = (x) => `${Number(x || 0).toFixed(3)} TND`;

/** Antifraude : alertes ouvertes (acquittement tracé) et numéros bloqués (levée manuelle confirmée). */
export function Fraud({ canWrite }) {
  const [all, setAll] = useState(false);
  const [alerts, reloadAlerts] = useLoad(() => api('/admin/fraud/alerts' + (all ? '?all=true' : '')), [all]);
  const [blocks, reloadBlocks] = useLoad(() => api('/admin/fraud/blocks'));
  const [msg, run, busy] = useAction();
  const confirm = useConfirm();
  const ack = (a) => run(() => api(`/admin/fraud/alerts/${a.id}/ack`, { method: 'POST' }), reloadAlerts);
  const lift = async (b) => { if (await confirm(t('confirmLift'))) run(() => api(`/admin/fraud/blocks/${b.id}`, { method: 'DELETE' }), reloadBlocks); };
  return (
    <>
      <Flash {...msg} error={msg.error || alerts.error || blocks.error} />
      <Card title={all ? t('fraudAll') : t('fraudAlerts')}>
        <label><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> {t('fraudAll')}</label>
        <Table caption={t('fraudAlerts')} cols={['createdAt', 'rule', 'msisdn', 'service', 'detail', 'status', ...(canWrite ? ['actions'] : [])]} labels={{ actions: t('actions') }}
          rows={alerts.data} render={{ actions: (a) => (a.status === 'OPEN' ? <Btn disabled={busy} onClick={() => ack(a)}>{t('acknowledge')}</Btn> : a.acknowledgedBy) }} />
      </Card>
      <Card title={t('fraudBlocks')}>
        <Table caption={t('fraudBlocks')} cols={['msisdn', 'rule', 'reason', 'createdAt', 'untilAt', ...(canWrite ? ['actions'] : [])]} labels={{ actions: t('actions') }}
          rows={blocks.data} render={{ actions: (b) => <Btn disabled={busy} onClick={() => lift(b)}>{t('lift')}</Btn> }} />
      </Card>
    </>
  );
}

/** Barres accessibles : graphique décoratif + tableau équivalent. */
function Bars({ rows, label, value, caption }) {
  if (!rows || rows.length === 0) return <p className="muted">{t('empty')}</p>;
  const max = Math.max(1, ...rows.map((r) => Number(r[value]) || 0));
  return (
    <div className="bars" role="img" aria-label={caption}>
      {rows.map((r) => <div key={r[label]} className="bar" style={{ height: `${Math.max(4, (100 * (Number(r[value]) || 0)) / max)}%` }} title={`${r[label]} : ${r[value]}`} />)}
    </div>
  );
}

const DIMS = [['day', 'byDay', 'revenueByDay'], ['operator', 'byOperator', 'revenueByOperator'], ['service', 'byService', 'revenueByService'], ['partner', 'byPartner', 'revenueByPartner'], ['type', 'byType', 'revenueByType']];

/** Informatique décisionnelle : indicateurs, chiffre d'affaires facturé par axe, abonnés, volumes par canal, exports. */
export function Analytics() {
  const today = new Date().toISOString().slice(0, 10);
  const monthAgo = new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10);
  const [range, setRange] = useState({ from: monthAgo, to: today });
  const [draft, setDraft] = useState(range);
  const [dim, setDim] = useState('day');
  const [{ data, error, loading }] = useLoad(() => api('/admin/analytics?' + qs(range)), [range.from, range.to]);
  const [msg, run] = useAction();
  const k = data?.kpis;
  const series = data ? data[DIMS.find((d) => d[0] === dim)[2]] : [];
  return (
    <>
      <Flash error={error || msg.error} />
      <Card>
        <form className="inline" onSubmit={(e) => { e.preventDefault(); setRange(draft); }}>
          <label>{t('from')} <input type="date" value={draft.from} max={draft.to} onChange={(e) => setDraft({ ...draft, from: e.target.value })} /></label>{' '}
          <label>{t('to')} <input type="date" value={draft.to} min={draft.from} onChange={(e) => setDraft({ ...draft, to: e.target.value })} /></label>{' '}
          <button className="btn sm">{t('apply')}</button>
        </form>
      </Card>
      {loading && <p className="muted">{t('loading')}</p>}
      {k && (
        <div className="stats">
          <Stat label={t('revenue')} value={tnd(k.grossCharged)} />
          <Stat label={t('arpu')} value={tnd(k.arpu)} />
          <Stat label={t('payers')} value={k.payingSubscribers} />
          <Stat label={t('activeSubs')} value={k.activeSubscriptions} />
          <Stat label={t('activations')} value={k.activations} />
          <Stat label={t('stops')} value={k.stops} />
          <Stat label={t('churn')} value={pct(k.churnRate)} />
          <Stat label={t('moReceived')} value={k.moReceived} />
          <Stat label={t('mtDelivered')} value={k.mtDelivered} />
          <Stat label={t('fraudAlerts')} value={k.fraudAlerts} />
        </div>
      )}
      {data && (
        <>
          <Card title={t('revenue')}>
            <div role="tablist" aria-label={t('revenue')}>
              {DIMS.map(([d, label]) => <button key={d} type="button" role="tab" aria-selected={dim === d} className={'btn sm' + (dim === d ? '' : ' ghost')} onClick={() => setDim(d)}>{t(label)}</button>)}
            </div>
            <Bars rows={series} label={dim} value="gross" caption={t('revenue')} />
            <Table caption={t('revenue')} cols={[dim, 'events', 'gross', 'operatorShare', 'partnerShare', 'providerShare', 'taxes']} rows={series.map((r) => ({ ...r, id: r[dim] }))} />
            {['csv', 'xlsx', 'pdf'].map((f) => <button key={f} type="button" className="btn sm" onClick={() => run(() => download(`/admin/analytics/export?${qs({ ...range, dimension: dim, format: f })}`, `ca-${dim}.${f}`))}>{t('export')} {f.toUpperCase()}</button>)}
          </Card>
          <Card title={t('subscribersByService')}>
            <Table caption={t('subscribersByService')} cols={['service', 'active']} rows={data.subscribers.activeByService.map((r) => ({ ...r, id: r.service }))} />
            <Table caption={t('activations')} cols={['day', 'activations', 'stops']} rows={data.subscribers.movementsByDay.map((r) => ({ ...r, id: r.day }))} />
          </Card>
          <Card title={t('channelsVolume')}>
            <Table caption={t('channelsVolume')} cols={['channel', 'count', 'detail']} rows={data.channels.map((r) => ({ ...r, id: r.channel }))} />
          </Card>
        </>
      )}
    </>
  );
}

const SAMPLE_MENU = '{"text":"Bienvenue\\n1. Voter\\n2. Info","options":{"1":{"text":"Vote A enregistre","action":"KEYWORD","param":"VOTE A"},"2":{"text":"Info du jour","action":"CHARGE"}}}';

/** Canaux : menus USSD / IVR (édition JSON validée par le serveur, aperçu sans effet) et comptes WhatsApp / RCS. */
export function Channels({ canWrite }) {
  const [channel, setChannel] = useState('USSD');
  const [menus, reload] = useLoad(() => api('/admin/channels/menus?channel=' + channel), [channel]);
  const [chats, reloadChats] = useLoad(() => api('/admin/channels/chat'));
  const [sv] = useLoad(() => api('/admin/services?size=500'));
  const [sc] = useLoad(() => api('/admin/shortcodes'));
  const [msg, run, busy] = useAction();
  const confirm = useConfirm();
  const [edit, setEdit] = useState(null);
  const [path, setPath] = useState('');
  const [prev, setPrev] = useState(null);
  const disable = async (m) => { if (await confirm(t('disable') + ' ' + m.code + ' ?', { danger: true })) run(() => api('/admin/channels/menus/' + m.id, { method: 'DELETE' }), reload); };
  const preview = () => run(async () => setPrev(await api('/admin/channels/menus/preview', { method: 'POST', body: { channel, definition: edit.definition, path } })));
  const services = (sv.data || []).map((s) => ({ value: s.id, label: s.name }));
  return (
    <>
      <Flash {...msg} />
      <Card title={t('menus')}>
        <label>{tc('channel')} <select value={channel} onChange={(e) => { setChannel(e.target.value); setEdit(null); }}><option>USSD</option><option>IVR</option></select></label>
        <p className="muted">{t('menuHelp')}</p>
        <Table caption={t('menus')} cols={['code', 'service', 'active', 'updatedBy', 'updatedAt', 'actions']} labels={{ actions: t('actions') }} rows={menus.data}
          render={{ actions: (m) => (<>
            <Btn onClick={() => { setEdit({ ...m }); setPrev(null); }}>{t('preview')}</Btn>
            {canWrite && m.active && <Btn disabled={busy} onClick={() => disable(m)}>{t('disable')}</Btn>}
          </>) }} />
        {canWrite && <Btn onClick={() => { setEdit({ code: channel === 'USSD' ? '*123#' : '', serviceId: '', definition: SAMPLE_MENU }); setPrev(null); }}>{t('create')}</Btn>}
        {edit && (
          <form className="form" onSubmit={(e) => { e.preventDefault(); run(() => api('/admin/channels/menus', { method: 'POST', body: { channel, code: edit.code, serviceId: +edit.serviceId, definition: edit.definition } }), () => { setEdit(null); reload(); }); }}>
            <div className="field"><label htmlFor="menu-code">{t('menuCode')} *</label><input id="menu-code" required value={edit.code} onChange={(e) => setEdit({ ...edit, code: e.target.value })} /></div>
            <div className="field"><label htmlFor="menu-svc">{t('services')} *</label>
              <select id="menu-svc" required value={edit.serviceId} onChange={(e) => setEdit({ ...edit, serviceId: e.target.value })}><option value="" />{services.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}</select></div>
            <div className="field"><label htmlFor="menu-def">{t('definition')} *</label>
              <textarea id="menu-def" required rows={10} spellCheck={false} value={edit.definition} onChange={(e) => setEdit({ ...edit, definition: e.target.value })} /></div>
            <div className="field"><label htmlFor="menu-path">{t('previewPath')}</label><input id="menu-path" value={path} onChange={(e) => setPath(e.target.value)} /></div>
            {prev && <p role="status" className="okmsg">{prev.invalid ? '⚠ ' : ''}{prev.text}{prev.end ? ` — ${prev.action}${prev.param ? ' « ' + prev.param + ' »' : ''}` : ''}</p>}
            <div className="actions">
              <button type="button" className="btn ghost" onClick={() => setEdit(null)}>{t('cancel')}</button>
              <button type="button" className="btn ghost" onClick={preview}>{t('preview')}</button>
              {canWrite && <button className="btn" disabled={busy}>{t('save')}</button>}
            </div>
          </form>
        )}
      </Card>
      <Card title={t('chatChannels')}>
        <Table caption={t('chatChannels')} cols={['kind', 'address', 'shortCode', 'operator', 'active']} rows={chats.data} />
        {canWrite && (
          <Form busy={busy} fields={[
            { name: 'kind', label: tc('kind'), required: true, options: ['WHATSAPP', 'RCS'] },
            { name: 'address', label: t('chatAddress'), required: true },
            { name: 'shortCodeId', label: t('shortcodes'), required: true, options: (sc.data || []).map((s) => ({ value: s.id, label: `${s.number} (${s.operator?.code ?? s.operator ?? ""})` })) }]}
            onSubmit={(v, done) => run(() => api('/admin/channels/chat', { method: 'POST', body: { ...v, shortCodeId: +v.shortCodeId } }), () => { done(); reloadChats(); })} />
        )}
      </Card>
    </>
  );
}

/** Paiements sur facture opérateur : suivi et remboursement (finance). */
export function Dcb({ canRefund }) {
  const [txs, reload] = useLoad(() => api('/admin/dcb/transactions'));
  const [msg, run, busy] = useAction();
  const confirm = useConfirm();
  const refund = async (x) => { if (await confirm(t('confirmRefund'), { danger: true })) run(() => api(`/admin/dcb/transactions/${x.id}/refund`, { method: 'POST' }), reload); };
  return (
    <>
      <Flash {...msg} error={msg.error || txs.error} />
      <Card title={t('dcb')}>
        <Table caption={t('dcb')} cols={['createdAt', 'service', 'operator', 'msisdn', 'amount', 'status', 'clientRef', 'error', ...(canRefund ? ['actions'] : [])]} labels={{ actions: t('actions') }}
          rows={(txs.data || []).map((x) => ({ ...x, amount: tnd(x.amount) }))} render={{ actions: (x) => (x.status === 'CHARGED' ? <Btn disabled={busy} onClick={() => refund(x)}>{t('refund')}</Btn> : null) }} />
      </Card>
    </>
  );
}

/** Bouton « Installer l'application » : affiché seulement quand le navigateur propose l'installation (Android, Chrome, Edge). */
export function InstallButton() {
  const [prompt, setPrompt] = useState(null);
  useEffect(() => {
    const on = (e) => { e.preventDefault(); setPrompt(e); };
    window.addEventListener('beforeinstallprompt', on);
    return () => window.removeEventListener('beforeinstallprompt', on);
  }, []);
  if (!prompt) return null;
  return <button type="button" className="btn sm ghost" onClick={async () => { prompt.prompt(); await prompt.userChoice.catch(() => {}); setPrompt(null); }}>{t('install')}</button>;
}

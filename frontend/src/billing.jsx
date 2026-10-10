import { useState } from 'react';
import { api, download } from './api.js';
import { t } from './i18n.js';
import { useLoad, Flash, Table, Form, Card, useConfirm, rules } from './ui.jsx';
import { useAction, Btn, iso } from './pages.jsx';

const range = [{ name: 'from', label: t('from'), type: 'datetime-local', required: true }, { name: 'to', label: t('to'), type: 'datetime-local', required: true }];
const rangeOk = (v) => (v.from && v.to && new Date(v.from) < new Date(v.to) ? null : t('invalidRange'));

/** Relevé d'un partenaire sur une période : tableau + exports (utilisé en back-office et dans le portail). */
function StatementView({ rows, name, url }) {
  const [msg, run] = useAction();
  if (!rows) return null;
  return (
    <>
      <Table caption={t('statement')} cols={['service', 'events', 'gross', 'partnerShare', 'estimatedPartnerShare']} rows={rows.map((r, i) => ({ id: i, ...r }))} />
      <Flash {...msg} />
      {['pdf', 'csv', 'xlsx'].map((f) => <button key={f} type="button" className="btn sm" onClick={() => run(() => download(`${url}&format=${f}`, `${name}.${f}`))}>{t('export')} {f.toUpperCase()}</button>)}
    </>
  );
}

/** Facturation : clôture de période, ajustements/remboursements, relevés partenaires, reversements (4 yeux). */
export function Billing({ canWrite }) {
  const [periods, reloadP] = useLoad(() => api('/admin/billing/periods'));
  const [payouts, reloadY] = useLoad(() => api('/admin/billing/payouts'));
  const [pa] = useLoad(() => api('/admin/partners?size=500'));
  const [msg, run, busy] = useAction(() => { reloadP(); reloadY(); });
  const [stmt, setStmt] = useState(null);
  const confirm = useConfirm();
  const [ref, setRef] = useState({});
  const partners = (pa.data || []).map((p) => ({ value: p.id, label: p.name }));
  const pay = (p) => run(() => api(`/admin/billing/payouts/${p.id}/pay`, { method: 'POST', body: { reference: ref[p.id] } }));
  const cancel = async (p) => { if (await confirm(t('confirmCancelPayout'), { danger: true })) run(() => api(`/admin/billing/payouts/${p.id}/cancel`, { method: 'POST' })); };
  return (
    <>
      <Flash error={periods.error || payouts.error} {...msg} />
      <Card title={t('billingPeriods')}>
        <Table caption={t('billingPeriods')} cols={['from', 'to', 'events', 'gross', 'operatorShare', 'partnerShare', 'providerShare', 'taxes', 'closedBy']} rows={periods.data} />
        {canWrite && <>
          <p className="muted">{t('closePeriodHint')}</p>
          <Form submit={t('closePeriod')} busy={busy} fields={range.map((f) => ({ ...f, validate: undefined }))}
            onSubmit={async (v, done) => { const e = rangeOk(v); if (e) return run(async () => { throw new Error(e); }); if (await confirm(t('confirmClosePeriod'), { danger: true })) run(() => api('/admin/billing/periods', { method: 'POST', body: { from: iso(v.from), to: iso(v.to) } }), done); }} />
        </>}
      </Card>
      {canWrite && (
        <Card title={t('adjustment')}>
          <p className="muted">{t('adjustmentHint')}</p>
          <Form submit={t('refund')} busy={busy} fields={[{ name: 'eventId', label: t('eventId'), required: true }, { name: 'reason', label: t('reason'), required: true }]}
            onSubmit={async (v, done) => { if (await confirm(t('confirmRefund'), { danger: true })) run(() => api('/admin/billing/adjustments', { method: 'POST', body: v }), done); }} />
        </Card>)}
      <Card title={t('statement')}>
        <Form submit={t('show')} busy={busy} fields={[{ name: 'partnerId', label: t('partners'), required: true, options: partners }, ...range]}
          onSubmit={(v) => { const e = rangeOk(v); if (e) return run(async () => { throw new Error(e); }); const url = `/admin/billing/statements?partnerId=${v.partnerId}&from=${encodeURIComponent(iso(v.from))}&to=${encodeURIComponent(iso(v.to))}`; run(async () => setStmt({ url, rows: await api(url) })); }} />
        {stmt && <StatementView rows={stmt.rows} name="releve" url={stmt.url} />}
      </Card>
      <Card title={t('payouts')}>
        <Table caption={t('payouts')} cols={['id', 'partner', 'from', 'to', 'amount', 'status', 'createdBy', 'paidBy', 'reference', 'actions']} labels={{ actions: t('actions') }} rows={payouts.data} render={{
          actions: (p) => canWrite && p.status === 'PENDING' && (
            <>
              <input aria-label={t('paymentReference')} placeholder={t('paymentReference')} value={ref[p.id] || ''} onChange={(e) => setRef({ ...ref, [p.id]: e.target.value })} />{' '}
              <Btn disabled={busy || !ref[p.id]} onClick={() => pay(p)}>{t('markPaid')}</Btn>{' '}
              <Btn disabled={busy} onClick={() => cancel(p)}>{t('cancel')}</Btn>
            </>) }} />
        {canWrite && (
          <Form submit={t('createPayout')} busy={busy} fields={[{ name: 'partnerId', label: t('partners'), required: true, options: partners }, ...range]}
            onSubmit={(v, done) => { const e = rangeOk(v); if (e) return run(async () => { throw new Error(e); }); run(() => api('/admin/billing/payouts', { method: 'POST', body: { partnerId: +v.partnerId, from: iso(v.from), to: iso(v.to) } }), done); }} />)}
      </Card>
    </>
  );
}

/** Portail partenaire : ses relevés par période et l'historique de ses reversements. */
export function PortalBilling() {
  const [payouts] = useLoad(() => api('/portal/payouts'));
  const [msg, run, busy] = useAction();
  const [stmt, setStmt] = useState(null);
  return (
    <>
      <Card title={t('statement')}>
        <Form submit={t('show')} busy={busy} fields={range}
          onSubmit={(v) => { const e = rangeOk(v); if (e) return run(async () => { throw new Error(e); }); const url = `/portal/statements?from=${encodeURIComponent(iso(v.from))}&to=${encodeURIComponent(iso(v.to))}`; run(async () => setStmt({ url, rows: await api(url) })); }} />
        <Flash {...msg} />
        {stmt && <StatementView rows={stmt.rows} name="releve" url={stmt.url} />}
      </Card>
      <Card title={t('payouts')}><Flash error={payouts.error} /><Table caption={t('payouts')} cols={['from', 'to', 'amount', 'status', 'paidAt', 'reference']} rows={payouts.data} /></Card>
    </>
  );
}

import { useState } from 'react';
import { api, download } from './api.js';
import { t } from './i18n.js';
import { useLoad, Flash, Table, Card, Stat } from './ui.jsx';

const pct = (x) => `${(100 * (x || 0)).toFixed(1)} %`;

/** Portail partenaire : uniquement ses services. Montants estimés et rapprochés clairement distingués. */
export function Portal() {
  const [{ data, error, loading }] = useLoad(() => api('/portal/summary'));
  const [svc, setSvc] = useState(null);
  const [res] = useLoad(() => (svc ? api('/portal/results?serviceId=' + svc) : Promise.resolve([])), [svc]);
  const [msg, setMsg] = useState({});
  const exp = async (f) => { try { await download('/portal/export?format=' + f, `rapport.${f}`); } catch (e) { setMsg({ error: e.message }); } };
  return (
    <>
      <Flash error={error} {...msg} />
      {loading && <p className="muted">{t('loading')}</p>}
      {data && (
        <>
          <h2>{data.partner}</h2>
          <div className="stats">
            <Stat label={`${t('estimated')} (TND)`} value={data.estimatedPartnerAmount.toFixed(3)} />
            <Stat label={`${t('reconciled')} (TND)`} value={data.reconciledPartnerAmount.toFixed(3)} />
          </div>
          {['csv', 'xlsx', 'pdf'].map((f) => <button key={f} className="btn sm" onClick={() => exp(f)}>{t('export')} {f.toUpperCase()}</button>)}
          {data.services.map((s) => (
            <Card key={s.serviceId} title={s.service}>
              <div className="stats">
                {Object.entries(s.mo).map(([k, v]) => <Stat key={'o' + k} label={'MO ' + k} value={v} />)}
                {Object.entries(s.mt).map(([k, v]) => <Stat key={'t' + k} label={'MT ' + k} value={v} />)}
                <Stat label={t('deliveryRate')} value={pct(s.deliveryRate)} />
              </div>
              <button className="btn sm" onClick={() => setSvc(s.serviceId)}>Résultats</button>
              {svc === s.serviceId && <Table cols={['content', 'count']} rows={res.data} />}
            </Card>
          ))}
          <Card title={t('ledger')}><Table cols={['status', 'grossAmount', 'partnerShare', 'events']} rows={Object.entries(data.billingByStatus).map(([k, v]) => ({ status: k, ...v }))} /></Card>
        </>
      )}
    </>
  );
}

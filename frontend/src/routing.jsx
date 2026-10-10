import { useState } from 'react';
import { api } from './api.js';
import { t } from './i18n.js';
import { useLoad, Flash, Table, Form, Card, useConfirm, rules } from './ui.jsx';
import { useAction, Btn } from './pages.jsx';

/** Routage des MT sans service : plages de numéros (plus long préfixe) et numéros portés (exception exacte). */
export function Routing({ canWrite }) {
  const [ranges, reload] = useLoad(() => api('/admin/routing/ranges'));
  const [msg, run, busy] = useAction(reload);
  const [found, setFound] = useState(null);
  const [kind, setKind] = useState('ranges');
  const [file, setFile] = useState(null);
  const [report, setReport] = useState(null);
  const confirm = useConfirm();
  const del = async (r) => { if (await confirm(t('confirmDelete'), { danger: true })) run(() => api('/admin/routing/ranges/' + r.id, { method: 'DELETE' })); };
  const upload = (e) => {
    e.preventDefault();
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    run(async () => { setReport(await api('/admin/routing/import?kind=' + kind, { method: 'POST', form })); });
  };
  return (
    <>
      <Flash error={ranges.error} {...msg} />
      <Card title={t('resolveNumber')}>
        <Form submit={t('resolve')} busy={busy} fields={[{ name: 'msisdn', label: t('msisdn'), required: true, validate: rules.msisdn }]}
          onSubmit={(v) => run(async () => setFound(await api('/admin/routing/resolve?msisdn=' + encodeURIComponent(v.msisdn))))} />
        {found && <p role="status">{found.routed ? `${found.msisdn} → ${found.operator}` : `${found.msisdn} : ${t('notRouted')}`}</p>}
      </Card>
      <Card title={t('numberRanges')}>
        <Table caption={t('numberRanges')} cols={['prefix', 'operator', 'source', 'actions']} labels={{ actions: t('actions') }} rows={ranges.data}
          render={{ actions: (r) => canWrite && <Btn disabled={busy} onClick={() => del(r)}>{t('delete')}</Btn> }} />
        <p className="muted">{t('routingHint')}</p>
      </Card>
      {canWrite && (
        <Card title={t('importCsv')}>
          <form className="form" onSubmit={upload}>
            <div className="field"><label htmlFor="rt-kind">{t('importKind')}</label>
              <select id="rt-kind" value={kind} onChange={(e) => setKind(e.target.value)}><option value="ranges">{t('numberRanges')}</option><option value="ported">{t('portedNumbers')}</option></select></div>
            <div className="field"><label htmlFor="rt-file">{t('file')}</label>
              <input id="rt-file" type="file" accept=".csv,text/csv,text/plain" onChange={(e) => setFile(e.target.files[0] || null)} /></div>
            <div className="actions"><button className="btn" disabled={busy || !file}>{busy ? t('saving') : t('importCsv')}</button></div>
          </form>
          {report && (
            <div role="status">
              <p>{`${t('created')} : ${report.created} · ${t('updated')} : ${report.updated} · ${t('rejected')} : ${report.rejected}`}</p>
              {report.errors.length > 0 && <ul>{report.errors.map((e) => <li key={e}>{e}</li>)}</ul>}
            </div>)}
        </Card>)}
    </>
  );
}

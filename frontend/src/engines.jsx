import { useState } from 'react';
import { api, download } from './api.js';
import { t } from './i18n.js';
import { useLoad, Flash, Table, Form, Card, Stat, useConfirm, rules } from './ui.jsx';
import { useAction, Btn, pct } from './pages.jsx';

/** Moteurs de service (vote, quiz, contenu premium) : saisie des options / questions / contenus d'un service. */
export function ServiceEngine({ service }) {
  if (service.type === 'VOTE') return <VoteOptions service={service} />;
  if (service.type === 'QUIZ') return <QuizQuestions service={service} />;
  if (service.type === 'PREMIUM_CONTENT') return <ContentItems service={service} />;
  return null;
}

function VoteOptions({ service }) {
  const [opt, reload] = useLoad(() => api('/admin/vote/options?serviceId=' + service.id), [service.id]);
  const [msg, run, busy] = useAction();
  const confirm = useConfirm();
  const del = async (o) => { if (await confirm(t('confirmDelete'), { danger: true })) run(() => api('/admin/vote/options/' + o.id, { method: 'DELETE' }), reload); };
  return (
    <section aria-label={t('voteOptions')}>
      <h4>{t('voteOptions')}</h4>
      <p className="muted">{t('voteHint')}</p>
      <Flash {...msg} />
      <Table caption={t('voteOptions')} cols={['code', 'label', 'actions']} labels={{ actions: t('actions') }} rows={opt.data} render={{ actions: (o) => <Btn disabled={busy} onClick={() => del(o)}>{t('delete')}</Btn> }} />
      <Form busy={busy} fields={[{ name: 'code', label: t('optionCode'), required: true, validate: (v) => (/^\S{1,20}$/.test(v) ? null : t('invalidValue')) }, { name: 'label', label: t('optionLabel'), required: true }]}
        onSubmit={(v, done) => run(() => api('/admin/vote/options', { method: 'POST', body: { serviceId: service.id, ...v } }), () => { done(); reload(); })} />
    </section>
  );
}

function QuizQuestions({ service }) {
  const [q, reload] = useLoad(() => api('/admin/quiz/questions?serviceId=' + service.id), [service.id]);
  const [msg, run, busy] = useAction();
  const confirm = useConfirm();
  const del = async (x) => { if (await confirm(t('confirmDelete'), { danger: true })) run(() => api('/admin/quiz/questions/' + x.id, { method: 'DELETE' }), reload); };
  return (
    <section aria-label={t('quizQuestions')}>
      <h4>{t('quizQuestions')}</h4>
      <Flash {...msg} />
      <Table caption={t('quizQuestions')} cols={['position', 'question', 'answers', 'points', 'actions']} labels={{ actions: t('actions') }} rows={q.data} render={{ actions: (x) => <Btn disabled={busy} onClick={() => del(x)}>{t('delete')}</Btn> }} />
      <Form busy={busy} fields={[
        { name: 'position', label: t('position'), type: 'number', min: 1, required: true }, { name: 'question', label: t('question'), required: true },
        { name: 'answers', label: t('acceptedAnswers'), required: true }, { name: 'points', label: t('points'), type: 'number', min: 1 },
        { name: 'replyCorrect', label: t('replyCorrect') }, { name: 'replyWrong', label: t('replyWrong') }]}
        onSubmit={(v, done) => run(() => api('/admin/quiz/questions', { method: 'POST', body: { serviceId: service.id, ...v, position: +v.position, points: v.points ? +v.points : 1 } }), () => { done(); reload(); })} />
    </section>
  );
}

function ContentItems({ service }) {
  const [it, reload] = useLoad(() => api('/admin/content/items?serviceId=' + service.id), [service.id]);
  const [msg, run, busy] = useAction();
  const confirm = useConfirm();
  const del = async (x) => { if (await confirm(t('confirmDelete'), { danger: true })) run(() => api('/admin/content/items/' + x.id, { method: 'DELETE' }), reload); };
  return (
    <section aria-label={t('contentItems')}>
      <h4>{t('contentItems')}</h4>
      <Flash {...msg} />
      <Table caption={t('contentItems')} cols={['code', 'title', 'maxUses', 'ttlHours', 'actions']} labels={{ actions: t('actions') }} rows={it.data} render={{ actions: (x) => <Btn disabled={busy} onClick={() => del(x)}>{t('delete')}</Btn> }} />
      <Form busy={busy} fields={[
        { name: 'code', label: t('optionCode'), required: true, validate: (v) => (/^\S{1,40}$/.test(v) ? null : t('invalidValue')) }, { name: 'title', label: t('title'), required: true },
        { name: 'body', label: t('contentBody') }, { name: 'url', label: 'URL', validate: (v) => (/^https:\/\/\S+$/.test(v) ? null : t('invalidValue')) },
        { name: 'maxUses', label: t('maxUses'), type: 'number', min: 1 }, { name: 'ttlHours', label: t('ttlHours'), type: 'number', min: 1 }]}
        onSubmit={(v, done) => run(() => api('/admin/content/items', { method: 'POST', body: { serviceId: service.id, ...v, maxUses: v.maxUses ? +v.maxUses : null, ttlHours: v.ttlHours ? +v.ttlHours : null } }), () => { done(); reload(); })} />
    </section>
  );
}

/** Histogramme accessible : barres décoratives + tableau équivalent (les chiffres ne dépendent pas de la vue graphique). */
export function HourlyBars({ series }) {
  if (!series || series.length === 0) return <p className="muted">{t('empty')}</p>;
  const max = Math.max(1, ...series.map((s) => s.mo));
  return (
    <>
      <div className="bars" role="img" aria-label={t('hourlyTraffic')}>
        {series.map((s) => <div key={s.hour} className="bar" style={{ height: `${Math.max(4, (100 * s.mo) / max)}%` }} title={`${new Date(s.hour).toLocaleString()} : ${s.mo}`} />)}
      </div>
      <details><summary>{t('showTable')}</summary>
        <Table caption={t('hourlyTraffic')} cols={['hour', 'mo', 'participants']} labels={{ hour: t('hours'), mo: 'MO' }} rows={series.map((s) => ({ ...s, id: s.hour, hour: new Date(s.hour).toLocaleString() }))} />
      </details>
    </>
  );
}

/** Pilotage de campagne : statistiques, trafic horaire, résultats, clôture (irréversible, confirmée) et exports. */
export function Campaigns() {
  const [sv] = useLoad(() => api('/admin/services?size=500'));
  const [id, setId] = useState('');
  const [hours, setHours] = useState(24);
  const [c, reload] = useLoad(() => (id ? api(`/admin/services/${id}/campaign?hours=${hours}`) : Promise.resolve(null)), [id, hours]);
  const [msg, run, busy] = useAction(reload);
  const confirm = useConfirm();
  const close = async () => { if (await confirm(t('confirmCloseCampaign'), { danger: true })) run(() => api(`/admin/services/${id}/close`, { method: 'POST' })); };
  const d = c.data;
  return (
    <>
      <Card>
        <label>{t('services')} <select value={id} onChange={(e) => setId(e.target.value)}><option value="" />{(sv.data || []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>{' '}
        <label>{t('hours')} <select value={hours} onChange={(e) => setHours(+e.target.value)}>{[1, 6, 24, 72, 168, 720].map((h) => <option key={h}>{h}</option>)}</select></label>
        <Flash error={c.error} {...msg} />
      </Card>
      {d && (
        <>
          <div className="stats">
            <Stat label={t('participants')} value={d.participants} />
            <Stat label={t('deliveryRate')} value={pct(d.deliveryRate)} />
            <Stat label={t('status')} value={d.closedAt ? `${d.status} (${t('closed')})` : d.status} />
            {Object.entries(d.mo).map(([k, v]) => <Stat key={'o' + k} label={'MO ' + k} value={v} />)}
            {Object.entries(d.mt).map(([k, v]) => <Stat key={'t' + k} label={'MT ' + k} value={v} />)}
          </div>
          <Card title={t('hourlyTraffic')}><HourlyBars series={d.series} /></Card>
          <Card title={t('results')}>
            <Table caption={t('results')} cols={Object.keys(d.results[0] || { content: 1, count: 1 })} rows={d.results.map((r, i) => ({ id: i, ...r }))} />
            {['pdf', 'csv', 'xlsx'].map((f) => <button key={f} type="button" className="btn sm" disabled={busy} onClick={() => run(() => download(`/admin/services/${id}/campaign/export?format=${f}`, `campagne-${id}.${f}`))}>{t('export')} {f.toUpperCase()}</button>)}
          </Card>
          {d.billing && <Card title={t('ledger')}><Table caption={t('ledger')} cols={['status', 'gross', 'partner', 'provider', 'events']} labels={{ status: t('status'), partner: t('partners'), provider: 'VAS' }} rows={Object.entries(d.billing).map(([k, v]) => ({ id: k, status: k, ...v }))} /></Card>}
          {!d.closedAt && <Card title={t('closeCampaign')}><p className="muted">{t('closeHint')}</p><button type="button" className="btn danger" disabled={busy} onClick={close}>{t('closeCampaign')}</button></Card>}
        </>
      )}
    </>
  );
}

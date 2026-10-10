import { createContext, useCallback, useContext, useEffect, useId, useRef, useState } from 'react';
import { apiPage } from './api.js';
import { t, tc } from './i18n.js';

/** Charge des données avec état chargement/erreur. */
export function useLoad(fn, deps = []) {
  const [state, set] = useState({ loading: true, data: null, error: null });
  const reload = useCallback(() => {
    set((s) => ({ ...s, loading: true, error: null }));
    fn().then((data) => set({ loading: false, data, error: null })).catch((e) => set({ loading: false, data: null, error: e.message }));
  }, deps); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(reload, [reload]);
  return [state, reload];
}

/** Liste paginée côté serveur (page, size, X-Total-Count). */
export function usePaged(path, params = {}, deps = []) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [state, set] = useState({ loading: true, rows: [], total: 0, error: null });
  const query = new URLSearchParams(Object.entries({ ...params, page, size }).filter(([, v]) => v !== '' && v != null)).toString();
  const reload = useCallback(() => {
    if (!path) { set({ loading: false, rows: [], total: 0, error: null }); return; }
    set((s) => ({ ...s, loading: true, error: null }));
    apiPage(`${path}?${query}`).then((r) => set({ loading: false, rows: r.rows, total: r.total, error: null })).catch((e) => set({ loading: false, rows: [], total: 0, error: e.message }));
  }, [path, query]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(reload, [reload, ...deps]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { setPage(0); }, [JSON.stringify(params)]); // eslint-disable-line react-hooks/exhaustive-deps
  return [state, { page, setPage, size, setSize, reload }];
}

export function Pager({ total, page, size, setPage, setSize }) {
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <nav className="pager" aria-label={t('page')}>
      <button type="button" className="btn sm" disabled={page <= 0} onClick={() => setPage(page - 1)}>‹ {t('previous')}</button>
      <span aria-live="polite">{t('page')} {page + 1} {t('of')} {pages} · {total} {t('rows')}</span>
      <button type="button" className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>{t('next')} ›</button>
      <label>{t('perPage')}
        <select value={size} onChange={(e) => { setSize(+e.target.value); setPage(0); }}>{[25, 50, 100, 200, 500].map((n) => <option key={n}>{n}</option>)}</select>
      </label>
    </nav>
  );
}

export const Flash = ({ error, ok }) => (error ? <p className="err" role="alert">{error}</p> : ok ? <p className="okmsg" role="status">{ok}</p> : null);

export function fmt(v) {
  if (v === null || v === undefined) return '';
  if (typeof v === 'object') return JSON.stringify(v);
  if (typeof v === 'boolean') return v ? '✓' : '—';
  return String(v);
}

/** Tableau accessible : légende, en-têtes de colonnes traduits (clé de donnée → libellé), render par colonne. */
export function Table({ cols, rows, render = {}, caption, labels = {} }) {
  if (!rows || rows.length === 0) return <p className="muted">{t('empty')}</p>;
  return (
    <div className="tbl" role="region" aria-label={caption || undefined} tabIndex={0}>
      <table>
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead><tr>{cols.map((c) => <th key={c} scope="col">{labels[c] ?? tc(c)}</th>)}</tr></thead>
        <tbody>{rows.map((r, i) => (
          <tr key={r.id ?? r.eventId ?? i}>{cols.map((c) => <td key={c}>{render[c] ? render[c](r) : fmt(r[c])}</td>)}</tr>
        ))}</tbody>
      </table>
    </div>
  );
}

/** Fenêtre modale accessible : rôle dialog, focus piégé, Échap, retour du focus à l'élément d'origine. */
export function Modal({ title, onClose, children }) {
  const ref = useRef(null);
  const titleId = useId();
  useEffect(() => {
    const opener = document.activeElement;
    const focusables = () => ref.current?.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])') ?? [];
    (focusables()[0] ?? ref.current)?.focus();
    const onKey = (e) => {
      if (e.key === 'Escape') { e.stopPropagation(); onClose(); }
      if (e.key === 'Tab') {
        const f = [...focusables()].filter((x) => !x.disabled);
        if (!f.length) return;
        const first = f[0], last = f[f.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); opener?.focus?.(); };
  }, [onClose]);
  return (
    <div className="overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={ref} tabIndex={-1}>
        <h3 id={titleId}>{title}</h3>
        {children}
      </div>
    </div>
  );
}

const ConfirmCtx = createContext(null);

/** Confirmation avant action destructrice : `const confirm = useConfirm(); if (await confirm(t('confirmDelete'))) ...`. */
export function ConfirmProvider({ children }) {
  const [req, setReq] = useState(null);
  const confirm = useCallback((message, opts = {}) => new Promise((resolve) => setReq({ message, resolve, ...opts })), []);
  const done = (v) => { req.resolve(v); setReq(null); };
  return (
    <ConfirmCtx.Provider value={confirm}>
      {children}
      {req && (
        <Modal title={req.title ?? t('confirm')} onClose={() => done(false)}>
          <p>{req.message}</p>
          <div className="actions">
            <button type="button" className="btn ghost" onClick={() => done(false)}>{t('cancel')}</button>
            <button type="button" className={'btn' + (req.danger ? ' danger' : '')} onClick={() => done(true)}>{t('confirm')}</button>
          </div>
        </Modal>
      )}
    </ConfirmCtx.Provider>
  );
}
export const useConfirm = () => useContext(ConfirmCtx) ?? (async () => true);

/** Validateurs réutilisables (renvoient un message ou null). */
export const rules = {
  msisdn: (v) => (!v || /^(\+?216)?[\s.-]?\d{2}[\s.-]?\d{3}[\s.-]?\d{3}$/.test(String(v).trim()) ? null : t('invalidMsisdn')),
  percent: (v) => (v === '' || v == null || (Number(v) >= 0 && Number(v) <= 100) ? null : t('percentRange')),
  positive: (v) => (v === '' || v == null || Number(v) > 0 ? null : t('positiveAmount')),
  minLength: (n) => (v) => (!v || String(v).length >= n ? null : t('minLength').replace('{n}', n)),
};

/** Formulaire avec validation : required, rules (fonctions), erreurs liées aux champs (aria-invalid / aria-describedby). */
export function Form({ fields, onSubmit, submit = t('create'), initial = {}, busy = false, onCancel }) {
  const [v, setV] = useState(initial);
  const [errors, setErrors] = useState({});
  const formRef = useRef(null);
  const base = useId();
  const validate = () => {
    const e = {};
    for (const f of fields) {
      const val = v[f.name];
      const empty = val === undefined || val === '' || val === null;
      if (f.required && empty && f.type !== 'checkbox') e[f.name] = t('required');
      else if (!empty && f.validate) { const m = f.validate(val); if (m) e[f.name] = m; }
    }
    setErrors(e);
    if (Object.keys(e).length) setTimeout(() => formRef.current?.querySelector('[aria-invalid="true"]')?.focus(), 0);
    return Object.keys(e).length === 0;
  };
  return (
    <form className="form" ref={formRef} noValidate onSubmit={(e) => { e.preventDefault(); if (validate()) onSubmit(v, () => { setV(initial); setErrors({}); }); }}>
      {fields.map((f) => {
        const id = `${base}-${f.name}`, err = errors[f.name];
        const common = { id, 'aria-invalid': err ? 'true' : undefined, 'aria-describedby': err ? `${id}-err` : undefined, 'aria-required': f.required || undefined };
        return (
          <div className="field" key={f.name}>
            <label htmlFor={id}>{f.label}{f.required ? ' *' : ''}</label>
            {f.options ? (
              <select {...common} value={v[f.name] ?? ''} onChange={(e) => setV({ ...v, [f.name]: e.target.value })}>
                <option value="" />{f.options.map((o) => <option key={o.value ?? o} value={o.value ?? o}>{o.label ?? o}</option>)}
              </select>
            ) : f.type === 'checkbox' ? (
              <input {...common} type="checkbox" checked={!!v[f.name]} onChange={(e) => setV({ ...v, [f.name]: e.target.checked })} />
            ) : (
              <input {...common} type={f.type || 'text'} value={v[f.name] ?? ''} step={f.step} min={f.min} max={f.max} autoComplete={f.autoComplete}
                onChange={(e) => setV({ ...v, [f.name]: e.target.value })} />
            )}
            {err && <span className="fielderr" id={`${id}-err`}>{err}</span>}
          </div>
        );
      })}
      <div className="actions">
        {onCancel && <button type="button" className="btn ghost" onClick={onCancel}>{t('cancel')}</button>}
        <button className="btn" disabled={busy}>{busy ? t('saving') : submit}</button>
      </div>
    </form>
  );
}

export const Card = ({ title, children }) => <section className="card">{title && <h3>{title}</h3>}{children}</section>;
export const Stat = ({ label, value }) => <div className="stat"><div className="statv">{value}</div><div className="muted">{label}</div></div>;

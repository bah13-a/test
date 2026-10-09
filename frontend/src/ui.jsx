import { useEffect, useState, useCallback } from 'react';
import { t } from './i18n.js';

/** Charge des données avec gestion d'état chargement/erreur. */
export function useLoad(fn, deps = []) {
  const [state, set] = useState({ loading: true, data: null, error: null });
  const reload = useCallback(() => {
    set((s) => ({ ...s, loading: true, error: null }));
    fn().then((data) => set({ loading: false, data, error: null })).catch((e) => set({ loading: false, data: null, error: e.message }));
  }, deps); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(reload, [reload]);
  return [state, reload];
}

export const Flash = ({ error, ok }) => (error ? <p className="err" role="alert">{error}</p> : ok ? <p className="okmsg" role="status">{ok}</p> : null);

export function Table({ cols, rows, render = {} }) {
  if (!rows || rows.length === 0) return <p className="muted">{t('empty')}</p>;
  return (
    <div className="tbl"><table>
      <thead><tr>{cols.map((c) => <th key={c}>{c}</th>)}</tr></thead>
      <tbody>{rows.map((r, i) => (
        <tr key={r.id ?? r.eventId ?? i}>{cols.map((c) => <td key={c}>{render[c] ? render[c](r) : fmt(r[c])}</td>)}</tr>
      ))}</tbody>
    </table></div>
  );
}

export function fmt(v) {
  if (v === null || v === undefined) return '';
  if (typeof v === 'object') return JSON.stringify(v);
  if (typeof v === 'boolean') return v ? '✓' : '—';
  return String(v);
}

export function Form({ fields, onSubmit, submit = t('create'), initial = {} }) {
  const [v, setV] = useState(initial);
  return (
    <form className="form" onSubmit={(e) => { e.preventDefault(); onSubmit(v, () => setV(initial)); }}>
      {fields.map((f) => (
        <label key={f.name}>{f.label}
          {f.options ? (
            <select value={v[f.name] ?? ''} required={f.required} onChange={(e) => setV({ ...v, [f.name]: e.target.value })}>
              <option value="" />{f.options.map((o) => <option key={o.value ?? o} value={o.value ?? o}>{o.label ?? o}</option>)}
            </select>
          ) : f.type === 'checkbox' ? (
            <input type="checkbox" checked={!!v[f.name]} onChange={(e) => setV({ ...v, [f.name]: e.target.checked })} />
          ) : (
            <input type={f.type || 'text'} value={v[f.name] ?? ''} required={f.required} step={f.step} onChange={(e) => setV({ ...v, [f.name]: e.target.value })} />
          )}
        </label>
      ))}
      <button className="btn">{submit}</button>
    </form>
  );
}

export const Card = ({ title, children }) => <section className="card">{title && <h3>{title}</h3>}{children}</section>;
export const Stat = ({ label, value }) => <div className="stat"><div className="statv">{value}</div><div className="muted">{label}</div></div>;

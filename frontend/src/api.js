// Client API : jeton Bearer obtenu par /auth/login (mémoire uniquement, jamais en localStorage).
let token = null; // jeton de session (30 min), en mémoire uniquement

export const setToken = (t) => { token = t; };
export const clearCreds = () => { token = null; };

/** Connexion : mot de passe (+ code TOTP si MFA actif) → jeton. Lève ApiError(mfa=true) si le code est requis. */
export async function login(username, password, code) {
  const r = await fetch('/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password, code: code || undefined }) });
  if (!r.ok) throw new ApiError(r.status, 'login', r.headers.get('X-MFA-Required') === 'true');
  const j = await r.json();
  token = j.token;
  return j;
}

export class ApiError extends Error {
  constructor(status, message, mfa) { super(message); this.status = status; this.mfa = !!mfa; }
}

function headers(extra = {}) {
  return { Authorization: token ? 'Bearer ' + token : '', ...extra };
}

async function check(r) {
  if (r.ok) return r;
  let msg = '';
  try { const j = await r.clone().json(); msg = j.message || j.error || ''; } catch { /* corps non JSON */ }
  throw new ApiError(r.status, msg || `HTTP ${r.status}`, r.headers.get('X-MFA-Required') === 'true');
}

export async function api(path, { method = 'GET', body, form } = {}) {
  const init = { method, headers: headers(body ? { 'Content-Type': 'application/json' } : {}) };
  if (body) init.body = JSON.stringify(body);
  if (form) init.body = form;
  const r = await check(await fetch(path, init));
  if (r.status === 204) return null;
  const ct = r.headers.get('content-type') || '';
  return ct.includes('json') ? r.json() : r.text();
}

export async function download(path, filename) {
  const r = await check(await fetch(path, { headers: headers() }));
  const url = URL.createObjectURL(await r.blob());
  const a = document.createElement('a');
  a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}

export const qs = (o) => new URLSearchParams(Object.entries(o).filter(([, v]) => v !== '' && v != null)).toString();

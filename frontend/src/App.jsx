import { useEffect, useState } from 'react';
import { api, login as apiLogin, clearCreds } from './api.js';
import { LANGS, getLang, setLang, t } from './i18n.js';
import * as P from './pages.jsx';
import { Portal } from './portal.jsx';
import { Flash, ConfirmProvider } from './ui.jsx';
import { Campaigns } from './engines.jsx';
import { Billing } from './billing.jsx';
import { Routing } from './routing.jsx';

const has = (me, ...roles) => roles.some((r) => me.roles.includes(r));

function menu(me) {
  if (has(me, 'PARTNER')) return [['portal', 'portal', () => <Portal />]];
  const m = [['dashboard', 'dashboard', () => <P.Dashboard />]];
  if (has(me, 'SUPER_ADMIN', 'NOC', 'VAS_MANAGER')) m.push(['operators', 'operators', () => <P.Operators />]);
  if (has(me, 'SUPER_ADMIN', 'NOC', 'VAS_MANAGER')) m.push(['routing', 'routing', () => <Routing canWrite />]);
  if (has(me, 'SUPER_ADMIN', 'NOC', 'VAS_MANAGER')) m.push(['shortcodes', 'shortcodes', () => <P.ShortCodes />]);
  if (has(me, 'SUPER_ADMIN', 'VAS_MANAGER', 'NOC', 'FINANCE', 'SUPPORT', 'AUDITOR')) m.push(['services', 'services', () => <P.Services />]);
  if (has(me, 'SUPER_ADMIN', 'VAS_MANAGER')) m.push(['partners', 'partners', () => <P.Partners />]);
  if (has(me, 'SUPER_ADMIN', 'FINANCE', 'VAS_MANAGER', 'AUDITOR')) m.push(['tariffs', 'tariffs', () => <P.Tariffs />]);
  if (has(me, 'SUPER_ADMIN', 'VAS_MANAGER', 'NOC', 'FINANCE', 'SUPPORT', 'AUDITOR')) m.push(['campaigns', 'campaigns', () => <Campaigns />]);
  m.push(['messages', 'messages', () => <P.Messages />]);
  if (has(me, 'SUPER_ADMIN', 'FINANCE', 'AUDITOR')) m.push(['ledger', 'ledger', () => <P.Ledger />]);
  if (has(me, 'SUPER_ADMIN', 'FINANCE', 'AUDITOR')) m.push(['billing', 'billing', () => <Billing canWrite={has(me, 'SUPER_ADMIN', 'FINANCE')} />]);
  if (has(me, 'SUPER_ADMIN', 'FINANCE', 'AUDITOR')) m.push(['reconciliation', 'reconciliation', () => <P.Reconciliation />]);
  if (has(me, 'SUPER_ADMIN', 'VAS_MANAGER', 'SUPPORT')) m.push(['rules', 'rules', () => <P.Rules />]);
  if (has(me, 'SUPER_ADMIN', 'SUPPORT', 'AUDITOR')) m.push(['support', 'support', () => <P.Support />]);
  if (has(me, 'SUPER_ADMIN')) { m.push(['users', 'users', () => <P.Users />]); m.push(['apiClients', 'apiClients', () => <P.ApiClients />]); }
  if (has(me, 'SUPER_ADMIN', 'AUDITOR')) m.push(['audit', 'audit', () => <P.Audit />]);
  return m;
}

export default function App() {
  const [me, setMe] = useState(null);
  const [page, setPage] = useState('dashboard');
  const [form, setForm] = useState({ u: '', p: '', c: '' });
  const [err, setErr] = useState(null);
  const [needMfa, setNeedMfa] = useState(false);
  const [env, setEnv] = useState('pro');
  const [mfaEnforced, setMfaEnforced] = useState(true);
  const [, force] = useState(0);
  useEffect(() => { fetch('/auth/env').then((r) => r.json()).then((j) => { setEnv(j.profile); setMfaEnforced(j.mfaEnforced !== false); }).catch(() => {}); }, []);
  const changeLang = (l) => { setLang(l); force((n) => n + 1); };

  const login = async (e) => {
    e.preventDefault();
    setErr(null);
    try {
      await apiLogin(form.u, form.p, form.c);
      const m = await api('/admin/me');
      setMe(m);
      // rôles sensibles sans MFA : enrôlement obligatoire avant tout autre écran
      const sensitive = m.roles.some((r) => ['SUPER_ADMIN', 'FINANCE'].includes(r));
      setPage(m.mustChangePassword ? 'security' : m.roles.includes('PARTNER') ? 'portal' : sensitive && !m.mfaEnabled && mfaEnforced ? 'security' : 'dashboard');
      setForm({ u: '', p: '', c: '' });
    } catch (ex) {
      clearCreds();
      if (ex.mfa) { setNeedMfa(true); setErr(t('mfaRequired')); } else setErr(ex.status === 401 ? t('badCreds') : ex.message);
    }
  };

  const banner = env === 'dev' && <div className="devbanner" role="note">{t('devBanner')}</div>;
  const skip = <a className="skip" href="#main">{t('skip')}</a>;
  const header = (
    <header>
      <b>{t('app')}</b>
      <select aria-label={t('language')} value={getLang()} onChange={(e) => changeLang(e.target.value)}>{Object.entries(LANGS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
      {me && <><span className="muted">{me.username} ({me.roles.join(', ')})</span><button className="btn sm" onClick={() => { clearCreds(); setMe(null); }}>{t('logout')}</button></>}
    </header>
  );

  if (!me) {
    return (
      <>
        {skip}{banner}{header}
        <main id="main"><form className="card login" onSubmit={login}>
          <h2>{t('login')}</h2>
          <div className="field"><label htmlFor="lg-u">{t('username')}</label><input id="lg-u" autoComplete="username" value={form.u} onChange={(e) => setForm({ ...form, u: e.target.value })} required /></div>
          <div className="field"><label htmlFor="lg-p">{t('password')}</label><input id="lg-p" type="password" autoComplete="current-password" value={form.p} onChange={(e) => setForm({ ...form, p: e.target.value })} required /></div>
          {(needMfa || form.c) && <div className="field"><label htmlFor="lg-c">{t('totp')}</label><input id="lg-c" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" value={form.c} onChange={(e) => setForm({ ...form, c: e.target.value })} /></div>}
          <button className="btn">{t('login')}</button>
          <Flash error={err} />
          <button type="button" className="link" onClick={() => setNeedMfa(true)}>{t('totp')}</button>
          {env === 'dev' && <p className="muted devhint">{t('devAccounts')}</p>}
        </form></main>
      </>
    );
  }

  if (me.mustChangePassword) { // mot de passe initial/réinitialisé : rien d'autre n'est accessible avant le changement
    return (
      <ConfirmProvider>
        {skip}{banner}{header}
        <main id="main"><h2>{t('changePassword')}</h2><P.ChangePassword forced onDone={() => { clearCreds(); setMe(null); setErr(t('passwordReconnect')); }} /></main>
      </ConfirmProvider>
    );
  }
  const items = menu(me);
  items.push(['security', 'security', () => <P.Security me={me} onChanged={(k) => { clearCreds(); setMe(null); setNeedMfa(true); setErr(t(k || 'mfaReconnect')); }} />]);
  const current = items.find((i) => i[0] === page) || items[0];
  return (
    <ConfirmProvider>
      {skip}{banner}{header}
      <div className="layout">
        <nav aria-label={t('navLabel')}>{items.map(([id, label]) => <button key={id} type="button" aria-current={id === current[0] ? 'page' : undefined} className={id === current[0] ? 'on' : ''} onClick={() => setPage(id)}>{t(label)}</button>)}</nav>
        <main id="main"><h2>{t(current[1])}</h2>{current[2]()}</main>
      </div>
    </ConfirmProvider>
  );
}

// Test d'intégration de bout en bout sur la VRAIE pile : application (profil pro) + PostgreSQL + Redis + RabbitMQ + Jasmin + simulateur SMSC SMPP.
//   MO : simulateur → SMPP → Jasmin → /callbacks/mo → moteur VAS → RabbitMQ → dispatcher → Jasmin /send → SMPP → simulateur → DLR → Jasmin → /callbacks/dlr
// Prérequis : pile démarrée (voir docs/14-tests-integration.md), base vierge, MFA imposé (profil pro).
//   BASE=http://localhost:8080 SIM=http://localhost:8081 ADMIN_USER=admin ADMIN_PASSWORD=... node tests/integration/full-stack.mjs
import crypto from 'node:crypto';

const BASE = process.env.BASE || 'http://localhost:8080', SIM = process.env.SIM || 'http://localhost:8081';
const ADMIN = process.env.ADMIN_USER || 'admin', PW = process.env.ADMIN_PASSWORD;
const SHORT = process.env.SHORTCODE || '85500';
const b32 = (s) => { const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; let bits = 0, v = 0; const o = []; for (const c of s) { v = (v << 5) | A.indexOf(c); bits += 5; if (bits >= 8) { o.push((v >>> (bits - 8)) & 255); bits -= 8; } } return Buffer.from(o); };
const totp = (secret) => { const c = Buffer.alloc(8); c.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000))); const h = crypto.createHmac('sha1', b32(secret)).update(c).digest(); const o = h[19] & 15; return String(((h.readUInt32BE(o) & 0x7fffffff) % 1e6)).padStart(6, '0'); };
let failed = 0;
const ok = (c, m) => { console.log((c ? 'OK    ' : 'ECHEC ') + m); if (!c) failed++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(fn, ms = 30000, step = 400) { const t = Date.now(); for (;;) { const v = await fn(); if (v) return v; if (Date.now() - t > ms) return null; await sleep(step); } }

async function login(u, p, code) {
  const r = await fetch(BASE + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, password: p, code }) });
  return r.ok ? (await r.json()).token : null;
}
const api = (tok) => async (path, method = 'GET', body) => {
  const r = await fetch(BASE + path, { method, headers: { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' }, body: body && JSON.stringify(body) });
  const txt = await r.text();
  try { return JSON.parse(txt); } catch { return txt; }
};
async function enroll(u, p) {
  const t = await login(u, p);
  const a = api(t);
  const { secret } = await a('/admin/me/mfa/setup', 'POST');
  await a('/admin/me/mfa/confirm', 'POST', { code: totp(secret) });
  return { secret, token: async () => login(u, p, totp(secret)) };
}
const simMessages = async () => (await (await fetch(SIM + '/messages')).json());

// ---------- préparation ----------
const admin = await enroll(ADMIN, PW);
const A = api(await admin.token());
ok((await A('/admin/me')).mfaEnabled === true, 'SUPER_ADMIN avec MFA actif');
for (const u of ['finance1', 'finance2']) await A('/admin/users', 'POST', { username: u, password: 'Finance-pass-12345', roles: ['FINANCE'] });
const f1 = await enroll('finance1', 'Finance-pass-12345'), f2 = await enroll('finance2', 'Finance-pass-12345');
const sc = (await A('/admin/shortcodes')).find((s) => s.number === SHORT);
ok(!!sc, `short code ${SHORT} synchronisé depuis la configuration`);
const partner = await A('/admin/partners', 'POST', { name: 'Club IT', sharePercent: 30 });
const svc = await A('/admin/services', 'POST', { name: 'Vote IT', type: 'VOTE', shortCodeId: sc.id, partnerId: partner.id });
await A(`/admin/services/${svc.id}/status/ACTIVE`, 'POST');
await A('/admin/keywords', 'POST', { serviceId: svc.id, word: 'VOTE' });
const tariff = await api(await f1.token())('/admin/tariffs', 'POST', { serviceId: svc.id, eventType: 'MT', grossAmount: 0.5, operatorPercent: 40, taxPercent: 19 });
await api(await f2.token())(`/admin/tariffs/${tariff.id}/approve`, 'POST');
const client = await A('/admin/api-clients', 'POST', { name: 'it', partnerId: partner.id, scopes: 'messages:send,messages:read', rateLimitPerMin: 100000 });
const K = { 'X-API-Key': client.apiKey, 'Content-Type': 'application/json' };
await fetch(SIM + '/reset');
ok(await until(async () => (await (await fetch(SIM + '/stats')).json()).bound === 1, 20000), 'Jasmin lié au SMSC (bind SMPP v3.4 établi)');

const messages = async (msisdn) => A('/admin/messages?msisdn=' + encodeURIComponent(msisdn));
const ledger = async () => A('/admin/ledger');

// ---------- 1. MO → MT → DLR → facturation ----------
await fetch(`${SIM}/mo?from=21698123456&to=${SHORT}&text=${encodeURIComponent('VOTE A')}`);
let m = await until(async () => (await messages('+21698123456')).find((x) => x.status === 'DELIVERED'));
ok(!!m, 'MO « VOTE A » → réponse MT livrée (DLR reçu via Jasmin)');
const ev = await until(async () => (await ledger()).find((e) => e.status === 'CHARGED'), 10000);
ok(!!ev, 'événement de facturation passé à CHARGED à la livraison');
const sub = (await simMessages()).find((x) => x.to.endsWith('98123456'));
// la réponse française contient « ç » (absent de l'alphabet GSM 03.38) : l'application doit choisir UCS-2 (data_coding 8)
ok(sub && sub.from === SHORT && sub.dataCoding === 8 && sub.text === 'Merci, votre message a bien été reçu.', `submit_sm reçu par le SMSC : expéditeur ${sub?.from}, data_coding ${sub?.dataCoding}, texte « ${sub?.text} »`);

// ---------- 2. Arabe (UCS-2) dans les deux sens ----------
await fetch(SIM + '/reset');
await fetch(`${SIM}/mo?from=21698765432&to=${SHORT}&text=${encodeURIComponent('vote أ')}&coding=8`);
const ar = await until(async () => (await simMessages()).find((x) => x.to.endsWith('98765432')));
ok(!!ar && ar.dataCoding === 8, `réponse à un MO arabe envoyée en UCS-2 (data_coding ${ar?.dataCoding})`);
ok(ar?.text === 'شكرا، تم استلام رسالتك.', `texte arabe intact de bout en bout : « ${ar?.text} »`);
const mAr = await until(async () => (await messages('+21698765432')).find((x) => x.status === 'DELIVERED'));
ok(!!mAr, 'le MO arabe a bien été compris (service vote routé) et le MT livré');

// ---------- 3. API : message long ----------
await fetch(SIM + '/reset');
const long = 'Bonjour, ceci est un message volontairement long pour vérifier la segmentation. '.repeat(4).trim(); // ~ 316 car.
const res = await (await fetch(BASE + '/api/v1/messages', { method: 'POST', headers: K, body: JSON.stringify({ to: '98111222', text: long, serviceId: svc.id, clientRef: 'long-1' }) })).json();
ok(res.segments >= 2, `API : message de ${long.length} caractères = ${res.segments} segments`);
const parts = (await until(async () => { const p = (await simMessages()).filter((x) => x.to.endsWith('98111222')); return p.length >= res.segments ? p : null; }, 20000)) || [];
ok(parts.length === res.segments, `SMSC : ${parts.length} submit_sm reçus pour ${res.segments} segments`);
ok(parts.some((p) => p.udh), 'concaténation UDH présente');
const longMt = await until(async () => (await messages('+21698111222')).find((x) => x.status === 'DELIVERED' || x.status === 'UNKNOWN'), 20000);
console.log(`

// ---------- 4. coupure du lien SMPP ----------
await fetch(SIM + '/reset');
await fetch(SIM + '/drop');
ok(await until(async () => (await (await fetch(SIM + '/stats')).json()).bound === 0, 5000, 200) !== null, 'lien SMPP coupé');
const during = await (await fetch(BASE + '/api/v1/messages', { method: 'POST', headers: K, body: JSON.stringify({ to: '98333444', text: 'pendant la coupure', serviceId: svc.id, clientRef: 'drop-1' }) })).json();
ok(!!during.id, 'MT accepté pendant la coupure (mis en file, pas perdu)');
ok(await until(async () => (await (await fetch(SIM + '/stats')).json()).bound === 1, 60000, 500) !== null, 'Jasmin se reconnecte tout seul');
const afterDrop = await until(async () => (await messages('+21698333444')).find((x) => x.status === 'DELIVERED'), 90000, 1000);
ok(!!afterDrop, 'le MT en attente est livré après reconnexion (aucune perte)');

// ---------- 5. débit : limiteur applicatif aligné sur le contrat, puis SMSC plus strict que prévu ----------
const tt = (await A('/admin/operators')).find((o) => o.code === 'TT');
await A('/admin/operators/' + tt.id, 'PATCH', { maxTps: 3 });
await fetch(SIM + '/reset');
await fetch(SIM + '/tps?v=3');
const N = 12;
const send = async (tag, i) => fetch(BASE + '/api/v1/messages', { method: 'POST', headers: K, body: JSON.stringify({ to: tag + String(1000 + i), text: 'debit ' + tag + i, serviceId: svc.id, clientRef: tag + i }) });
for (let i = 0; i < N; i++) await send('9855', i);
const deliveredFor = async (prefix) => (await A('/admin/messages?status=DELIVERED')).filter((x) => x.msisdn.includes(prefix)).length;
ok(await until(async () => (await deliveredFor('9855')) >= N, 120000, 1500) !== null, `${N} MT livrés avec le limiteur applicatif à 3 SMS/s (aligné sur le SMSC)`);
let st = await (await fetch(SIM + '/stats')).json();
ok(st.throttled === 0, `aucun rejet ESME_RTHROTTLED : l'application respecte le débit contractuel (rejets : ${st.throttled})`);

// SMSC plus strict que le débit configuré : aucun message perdu côté SMSC (Jasmin réessaie)
await A('/admin/operators/' + tt.id, 'PATCH', { maxTps: 50 });
await fetch(SIM + '/reset');
for (let i = 0; i < N; i++) await send('9856', i);
ok(await until(async () => (await (await fetch(SIM + '/stats')).json()).submitted >= N, 120000, 1500) !== null, `les ${N} MT parviennent tous au SMSC malgré son throttling (Jasmin réessaie)`);
st = await (await fetch(SIM + '/stats')).json();
ok(st.throttled > 0, `le SMSC a bien limité le débit (${st.throttled} rejets absorbés)`);
ok((await A('/admin/messages?status=FAILED')).length === 0, 'aucun MT en échec définitif');
await fetch(SIM + '/tps?v=0');

console.log(failed === 0 ? '\nINTÉGRATION COMPLÈTE : TOUT EST OK' : `\n${failed} ÉCHEC(S)`);
process.exit(failed === 0 ? 0 : 1);

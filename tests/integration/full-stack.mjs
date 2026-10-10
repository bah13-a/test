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
const status = (tok) => async (path, method = 'GET', body) => (await fetch(BASE + path, { method, headers: { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' }, body: body && JSON.stringify(body) })).status;
const simMessages = async () => (await (await fetch(SIM + '/messages')).json());

// ---------- préparation ----------
const admin = await enroll(ADMIN, PW);
const A = api(await admin.token());
ok((await A('/admin/me')).mfaEnabled === true, 'SUPER_ADMIN avec MFA actif');
for (const u of ['finance1', 'finance2']) await A('/admin/users', 'POST', { username: u, password: 'Finance-pass-12345', roles: ['FINANCE'] });
// compte créé par un administrateur : mot de passe à changer avant tout autre accès (puis anciennes sessions révoquées)
const FP = 'Finance-pass-67890';
{
  const t0 = await login('finance1', 'Finance-pass-12345');
  ok((await fetch(BASE + '/admin/operators', { headers: { Authorization: 'Bearer ' + t0 } })).status === 403, 'compte neuf : accès refusé tant que le mot de passe n\'est pas changé');
  for (const u of ['finance1', 'finance2']) { const t = await login(u, 'Finance-pass-12345'); await api(t)('/admin/me/password', 'POST', { current: 'Finance-pass-12345', newPassword: FP }); }
  ok((await fetch(BASE + '/admin/me', { headers: { Authorization: 'Bearer ' + t0 } })).status === 401, 'ancienne session révoquée après changement de mot de passe');
}
const f1 = await enroll('finance1', FP), f2 = await enroll('finance2', FP);
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
// Constaté sur Jasmin 0.10.13 réel + simulateur : l'accusé final d'un message concaténé se perd par intermittence (≈ 1 fois sur 3 sur de petites rafales).
// On envoie donc 3 messages longs : au moins un doit aboutir (le mécanisme fonctionne) ; les autres restent SUBMITTED jusqu'au délai de DLR
// (UNKNOWN + facturation DISPUTED, alertes DlrTimeoutMultipart) - voir docs/14-tests-integration.md.
for (const n of ['98111223', '98111224']) await fetch(BASE + '/api/v1/messages', { method: 'POST', headers: K, body: JSON.stringify({ to: n, text: long, serviceId: svc.id, clientRef: 'long-' + n }) });
await sleep(8000);
const finals = [];
for (const n of ['98111222', '98111223', '98111224']) finals.push(((await messages('+216' + n)).find((x) => x.status === 'DELIVERED') ? 1 : 0));
console.log(`      messages longs livrés : ${finals.reduce((a, b) => a + b, 0)} / 3`);
ok(finals.some((v) => v === 1), 'message long : au moins un accusé final reçu (DLR du dernier segment) ; les accusés perdus relèvent du délai de DLR');

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

// ---------- 6. OAuth2, webhooks, envoi programmé, facturation (points 12 à 14) ----------
{
  const form = new URLSearchParams({ grant_type: 'client_credentials', client_id: 'client-' + client.id, client_secret: client.apiKey });
  const tok = await (await fetch(BASE + '/oauth/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form })).json();
  ok(tok.token_type === 'Bearer' && !!tok.access_token, 'OAuth2 client_credentials : jeton Bearer émis');
  const apiSt = (await fetch(BASE + '/api/v1/services', { headers: { Authorization: 'Bearer ' + tok.access_token } })).status;
  ok(apiSt === 403 || apiSt === 200, `jeton OAuth2 accepté par l'API (statut ${apiSt} ; 403 = scope absent, jamais 401)`);
  ok((await fetch(BASE + '/oauth/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'client_credentials', client_id: 'client-' + client.id, client_secret: 'faux' }) })).status === 401, 'OAuth2 : mauvais secret refusé');
  const S = status(await admin.token());
  ok((await S('/admin/partners/' + partner.id, 'PATCH', { webhookUrl: 'http://example.com/hook' })) === 422, 'webhook en http refusé en production (422, https obligatoire)');
  ok((await S('/admin/partners/' + partner.id, 'PATCH', { webhookUrl: 'https://169.254.169.254/latest/meta-data' })) === 422, 'webhook vers adresse metadata/privée refusé (422, SSRF)');

  const at = new Date(Date.now() + 3600e3).toISOString();
  const sch = await (await fetch(BASE + '/api/v1/messages', { method: 'POST', headers: K, body: JSON.stringify({ to: '98777666', text: 'programmé', serviceId: svc.id, clientRef: 'sched-1', scheduleAt: at }) })).json();
  await sleep(4000);
  const schMt = (await A('/admin/messages?msisdn=98777666'))[0];
  ok(schMt && schMt.status === 'PENDING', 'MT programmé à +1 h : non envoyé (PENDING)');

  const from = new Date(Date.now() - 86400e3).toISOString(), to = new Date(Date.now() + 1000).toISOString();
  const F1 = api(await f1.token()), F2 = api(await f2.token());
  ok((await status(await f1.token())('/admin/billing/periods', 'POST', { from, to: new Date(Date.now() - 1000).toISOString() })) === 409, 'clôture refusée (409) tant que des événements ne sont pas rapprochés');
  const evs = (await F1('/admin/ledger?status=CHARGED&size=5')) || [];
  if (evs.length) {
    const rev = await F1('/admin/billing/adjustments', 'POST', { eventId: evs[0].eventId, reason: 'test intégration' });
    ok(rev.mode === 'REVERSED', 'remboursement en période ouverte : événement REVERSED');
  }
  const stmt = await F1(`/admin/billing/statements?partnerId=${partner.id}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
  ok(Array.isArray(stmt) && stmt.some((r) => r.service === 'TOTAL'), 'relevé partenaire (JSON) avec total');
}

// ---------- 7. CQRS : modèles de lecture cohérents avec les tables d'écriture, côté requête sur le réplica (ici : même base, routage actif) ----------
{
  const dash = await A('/admin/dashboard?hours=24');
  ok((dash.mo.ROUTED || 0) >= 1 && (dash.mt.DELIVERED || 0) >= 1, `tableau de bord lu sur les modèles de lecture : MO routés ${dash.mo.ROUTED}, MT livrés ${dash.mt.DELIVERED}`);
  const camp = await A(`/admin/services/${svc.id}/campaign?hours=24`);
  ok((camp.mt.DELIVERED || 0) >= 1 && camp.billing && Object.keys(camp.billing).length > 0, 'campagne : compteurs MT et facturation issus des projections');
  const msgs = await A('/admin/messages?size=5');
  ok(Array.isArray(msgs) && msgs.length > 0, 'recherche de messages (côté requête) : numéros déchiffrés / masqués correctement');
  const route = await A('/admin/routing/resolve?msisdn=98123456');
  ok(route.routed === true && route.operator === 'TT', 'routage : préfixes déclarés (TT_MSISDN_PREFIXES) pris en compte à défaut de plage importée');
}

// ---------- amorçage piloté par le .env (ROUTING_RANGES_FILE, ROUTING_PORTED_FILE, CATALOG_FILE) ----------
{
  const ranges = await A('/admin/routing/ranges');
  ok(Array.isArray(ranges) && ranges.some((r) => r.prefix === '7777' && r.operator === 'ORANGE'), 'plages de routage chargées depuis ROUTING_RANGES_FILE');
  const svcs = await A('/admin/services'), cat = Array.isArray(svcs) && svcs.find((x) => x.name === 'Service Catalogue');
  ok(!!cat && cat.status === 'DRAFT', 'catalogue chargé depuis CATALOG_FILE : service créé en BROUILLON');
  const tf = cat ? (await A('/admin/tariffs?serviceId=' + cat.id)) : [];
  ok(Array.isArray(tf) && tf.length === 1 && tf[0].approved === false, 'tarif du catalogue non approuvé (double validation conservée)');
}
console.log(failed === 0 ? '\nINTÉGRATION COMPLÈTE : TOUT EST OK' : `\n${failed} ÉCHEC(S)`);
process.exit(failed === 0 ? 0 : 1);

// Test de charge MO + API (Node >= 18, aucune dépendance). Usage :
//   BASE=http://localhost:8080 ADMIN=admin:motdepasse CALLBACK_SECRET=... node tests/load/load.mjs [total=2000] [concurrence=50]
// Le service/short code de test est créé automatiquement (nom "LOAD-TEST"). À exécuter contre le SIT (simulateur), jamais en production.
const BASE = process.env.BASE || 'http://localhost:8080';
const ADMIN = 'Basic ' + Buffer.from(process.env.ADMIN || 'admin:admin').toString('base64');
const SECRET = process.env.CALLBACK_SECRET || 'dev-secret';
const TOTAL = +(process.argv[2] || 2000), CONC = +(process.argv[3] || 50);

async function call(path, { method = 'GET', body, headers = {} } = {}) {
  const r = await fetch(BASE + path, { method, headers: { Authorization: ADMIN, 'Content-Type': 'application/json', ...headers }, body: body && JSON.stringify(body) });
  return r;
}
const pct = (a, p) => a.sort((x, y) => x - y)[Math.min(a.length - 1, Math.floor(a.length * p))];

async function run(label, n, conc, fn) {
  const lat = []; let err = 0, next = 0;
  const t0 = performance.now();
  await Promise.all(Array.from({ length: conc }, async () => {
    while (next < n) { const i = next++; const s = performance.now(); try { const ok = await fn(i); if (!ok) err++; } catch { err++; } lat.push(performance.now() - s); }
  }));
  const sec = (performance.now() - t0) / 1000;
  console.log(`${label}: ${n} req en ${sec.toFixed(1)} s = ${(n / sec).toFixed(0)} req/s | P50 ${pct(lat, .5).toFixed(0)} ms | P95 ${pct(lat, .95).toFixed(0)} ms | P99 ${pct(lat, .99).toFixed(0)} ms | erreurs ${err}`);
  return { rps: n / sec, p95: pct(lat, .95), err };
}

// --- préparation ---
const sc = await (await call('/admin/shortcodes', { method: 'POST', body: { number: '7' + Date.now().toString().slice(-5), operatorCode: 'TT' } })).json();
const svc = await (await call('/admin/services', { method: 'POST', body: { name: 'LOAD-TEST', type: 'VOTE', shortCodeId: sc.id } })).json();
await call(`/admin/services/${svc.id}/status/ACTIVE`, { method: 'POST' });
await call('/admin/keywords', { method: 'POST', body: { serviceId: svc.id, word: 'LOAD' } });
const key = (await (await call('/admin/api-clients', { method: 'POST', body: { name: 'load', scopes: 'messages:send,messages:read', rateLimitPerMin: 10000000 } })).json()).apiKey;

const run1 = await run('MO  /callbacks/mo   ', TOTAL, CONC, async (i) => {
  const from = '9' + String(10000000 + (i % 9000000)).slice(1);
  const q = new URLSearchParams({ secret: SECRET, id: `load-${Date.now()}-${i}`, from, to: sc.number, content: 'LOAD ' + i, 'origin-connector': 'smppc_tt' });
  const r = await fetch(`${BASE}/callbacks/mo?${q}`, { method: 'POST' });
  return r.ok && (await r.text()) === 'ACK/Jasmin';
});
const run2 = await run('API POST /messages  ', TOTAL, CONC, async (i) => {
  const r = await fetch(`${BASE}/api/v1/messages`, { method: 'POST', headers: { 'X-API-Key': key, 'Content-Type': 'application/json' },
    body: JSON.stringify({ to: '98' + String(100000 + i).slice(-6), text: 'bonjour', serviceId: svc.id, clientRef: `l-${Date.now()}-${i}` }) });
  return r.status === 202;
});
const dash = await (await call('/admin/dashboard?hours=1')).json();
console.log('MT par statut :', JSON.stringify(dash.mt), '| MT en attente :', dash.pending);
// objectifs CDC §12.1 : API acceptation P95 < 500 ms ; MO interne P95 < 2 s
const ok = run1.p95 < 2000 && run2.p95 < 500 && run1.err + run2.err === 0;
console.log(ok ? 'OBJECTIFS CDC ATTEINTS (P95 MO < 2 s, P95 API < 500 ms, 0 erreur)' : 'OBJECTIFS CDC NON ATTEINTS');
process.exit(ok ? 0 : 1);

// Back-office minimal V1 (lecture) : consomme l'API /admin. Les identifiants restent en mémoire (jamais stockés).
const views = {
  Opérateurs: { url: '/admin/operators', cols: ['code', 'name', 'status', 'maxTps', 'dlrBillingRule'] },
  Services: { url: '/admin/services', cols: ['id', 'name', 'type', 'status', 'regulated', 'regulatoryApproved', 'consentMode'] },
  Messages: { url: '/admin/messages', cols: ['createdAt', 'msisdn', 'sender', 'status', 'rawStatus', 'segments', 'attempts'] },
  Ledger: { url: '/admin/ledger', cols: ['eventId', 'eventType', 'grossAmount', 'operatorShare', 'providerShare', 'partnerShare', 'billingStatus'] },
  Audit: { url: '/admin/audit', cols: ['at', 'actor', 'action', 'target', 'detail'] },
};
let auth = null, current = 'Opérateurs';
const $ = (id) => document.getElementById(id);
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const maskMsisdn = (k, v) => (k === 'msisdn' && v ? String(v).slice(0, -5) + '*****' : v);

$('nav').innerHTML = Object.keys(views).map((k) => `<button data-v="${esc(k)}">${esc(k)}</button>`).join('');
$('nav').onclick = (e) => { if (e.target.dataset.v) { current = e.target.dataset.v; load(); } };
$('login').onsubmit = (e) => { e.preventDefault(); auth = 'Basic ' + btoa(unescape(encodeURIComponent($('u').value + ':' + $('p').value))); load(); };

async function load() {
  document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('on', b.dataset.v === current));
  if (!auth) return;
  const v = views[current];
  $('msg').textContent = 'Chargement…'; $('msg').className = 'muted';
  try {
    const r = await fetch(v.url, { headers: { Authorization: auth } });
    if (!r.ok) throw new Error(r.status === 401 ? 'Identifiants invalides' : r.status === 403 ? 'Droits insuffisants pour cette vue' : 'Erreur ' + r.status);
    const rows = await r.json();
    $('msg').textContent = rows.length + ' ligne(s)';
    $('out').innerHTML = '<table><thead><tr>' + v.cols.map((c) => `<th>${esc(c)}</th>`).join('') + '</tr></thead><tbody>' +
      rows.map((row) => '<tr>' + v.cols.map((c) => `<td>${esc(maskMsisdn(c, row[c]))}</td>`).join('') + '</tr>').join('') + '</tbody></table>';
  } catch (e) { $('msg').textContent = e.message; $('msg').className = 'err'; $('out').innerHTML = ''; }
}
load();

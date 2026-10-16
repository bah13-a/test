// Recette d'un opérateur réel (étape 1 du passage en production) : MT vers une SIM de test, suivi jusqu'à l'accusé final,
// puis procès-verbal Markdown pré-rempli (cas automatiques + cas manuels à cocher sur le téléphone). Node >= 18, aucune dépendance.
//   BASE=https://vas.client.tn API_KEY=... OPERATOR=TT SIM=98xxxxxx SENDER=85500 node tests/recette/operator-check.mjs
// Variables facultatives : SERVICE_ID (au lieu de SENDER), TIMEOUT_S (180), OUT (fichier du PV, défaut recette-<OP>-<date>.md).
import { writeFileSync } from 'node:fs';

const BASE = process.env.BASE || 'http://localhost:8080';
const KEY = process.env.API_KEY, OP = (process.env.OPERATOR || 'TT').toUpperCase(), SIM = process.env.SIM;
const SENDER = process.env.SENDER, SERVICE = process.env.SERVICE_ID ? +process.env.SERVICE_ID : undefined;
const TIMEOUT = +(process.env.TIMEOUT_S || 180) * 1000;
if (!KEY || !SIM || (!SENDER && !SERVICE)) {
  console.error('API_KEY, SIM et SENDER (ou SERVICE_ID) sont obligatoires');
  process.exit(2);
}
const FINAL = new Set(['DELIVERED', 'UNDELIVERABLE', 'FAILED', 'REJECTED', 'EXPIRED', 'UNKNOWN']);
const run = `${OP}-${new Date().toISOString().replace(/\D/g, '').slice(0, 12)}`;

async function api(path, init = {}) {
  const r = await fetch(BASE + path, { ...init, headers: { 'X-API-Key': KEY, 'Content-Type': 'application/json', ...(init.headers || {}) } });
  const body = await r.text();
  let json = null;
  try { json = JSON.parse(body); } catch { /* corps non JSON */ }
  return { status: r.status, json, body };
}

async function send(text, ref) {
  const r = await api('/api/v1/messages', { method: 'POST', body: JSON.stringify({ to: SIM, text, operator: OP, sender: SENDER, serviceId: SERVICE, clientRef: `${run}-${ref}` }) });
  if (r.status !== 202 && r.status !== 200) throw new Error(`envoi refusé (${r.status}) : ${r.body.slice(0, 200)}`);
  return r.json;
}

async function waitFinal(id) {
  const t0 = Date.now();
  let last;
  while (Date.now() - t0 < TIMEOUT) {
    last = (await api(`/api/v1/messages/${encodeURIComponent(id)}`)).json;
    if (last && FINAL.has(last.status)) return { ...last, seconds: Math.round((Date.now() - t0) / 1000) };
    await new Promise((r) => setTimeout(r, 3000));
  }
  return { ...(last || {}), seconds: Math.round(TIMEOUT / 1000), timeout: true };
}

const CASES = [
  { ref: 'R1', label: 'MT court, alphabet GSM 03.38', text: `Recette ${OP} R1 : message court GSM. Ref ${run}`, expect: { encoding: 'GSM7', segments: 1 } },
  { ref: 'R2', label: 'MT arabe et accents (UCS-2)', text: `اختبار ${OP} R2 : رسالة عربية — é à ç`, expect: { encoding: 'UCS2', segments: 1 } },
  { ref: 'R3', label: 'MT long, 3 segments concaténés (UDH)', text: `Recette ${OP} R3 : message long. ` + 'Le texte doit arriver en un seul message sur le téléphone. '.repeat(7), expect: { encoding: 'GSM7', segments: 3 } },
];

const health = await api('/api/v1/health');
console.log(`Plateforme ${BASE} : ${health.status === 200 ? 'joignable' : 'ERREUR ' + health.status}`);
const results = [];
for (const c of CASES) {
  try {
    const m = await send(c.text, c.ref);
    process.stdout.write(`${c.ref} ${c.label} : envoyé (${m.id}, ${m.segments} segment(s), ${m.encoding}) … `);
    const f = await waitFinal(m.id);
    const ok = f.status === 'DELIVERED' && m.encoding === c.expect.encoding && m.segments === c.expect.segments;
    console.log(`${f.status || '?'} en ${f.seconds} s${f.timeout ? ' (délai dépassé)' : ''} → ${ok ? 'OK' : 'ÉCHEC'}`);
    results.push({ ...c, id: m.id, encoding: m.encoding, segments: m.segments, status: f.status, raw: f.rawStatus, seconds: f.seconds, ok });
  } catch (e) {
    console.log(`${c.ref} ${c.label} : ÉCHEC ${e.message}`);
    results.push({ ...c, status: 'ERREUR', raw: e.message, ok: false });
  }
}

const manual = [
  ['M1', `Sur la SIM : R1, R2 et R3 reçus, expéditeur ${SENDER || 'du service'}, texte intact (arabe lisible, R3 en un seul message)`],
  ['M2', 'Envoyer un mot-clé de service depuis la SIM (MO) : réponse reçue, MO visible dans Messages, événement de facturation au bon tarif'],
  ['M3', 'Envoyer STOP (puis توقف) : désabonnement confirmé, plus aucun envoi payant'],
  ['M4', "Accusés : statut final et code d'erreur de l'opérateur conformes à sa documentation (numéro éteint, numéro inexistant)"],
  ['M5', 'Débit : envoi au débit contractuel (`' + OP + '_TPS`) sans refus définitif ; ESME_RTHROTTLED absorbé'],
  ['M6', "Coupure de la liaison (demandée à l'opérateur) : reconnexion automatique, aucun message perdu"],
  ['M7', "Relevé de facturation de l'opérateur importé : rapprochement sans écart (Facturation → Rapprochement)"],
  ['M8', "Connexion par le VPN de l'opérateur uniquement ; filtrage des adresses vérifié"],
];
const okCount = results.filter((r) => r.ok).length;
const out = process.env.OUT || `recette-${run}.md`;
writeFileSync(out, [
  `# Procès-verbal de recette opérateur — ${OP}`, '',
  `Plateforme : ${BASE} · SIM de test : ${SIM} · Expéditeur : ${SENDER || 'service ' + SERVICE} · Exécution : ${new Date().toISOString()} · Référence : ${run}`, '',
  `## Cas automatiques (${okCount} / ${results.length} OK)`, '',
  '| Cas | Description | Identifiant | Codage | Segments | Statut final | Détail opérateur | Délai | Résultat |', '|---|---|---|---|---|---|---|---|---|',
  ...results.map((r) => `| ${r.ref} | ${r.label} | ${r.id || '-'} | ${r.encoding || '-'} | ${r.segments ?? '-'} | ${r.status || '-'} | ${String(r.raw || '-').replace(/\|/g, '/')} | ${r.seconds ?? '-'} s | ${r.ok ? 'OK' : 'ÉCHEC'} |`), '',
  '## Cas manuels', '', '| Cas | Vérification | Résultat | Observations |', '|---|---|---|---|',
  ...manual.map(([id, t]) => `| ${id} | ${t} | ☐ OK ☐ KO | |`), '',
  '## Décision', '', '☐ Recette prononcée sans réserve ☐ avec réserves (ci-dessus) ☐ refusée', '',
  "| | Nom | Date | Signature |", '|---|---|---|---|', `| Opérateur ${OP} | | | |`, '| Client | | | |', '| Prestataire | | | |', '',
].join('\n'));
console.log(`\n${okCount} / ${results.length} cas automatiques OK — procès-verbal : ${out}`);
process.exit(okCount === results.length ? 0 : 1);

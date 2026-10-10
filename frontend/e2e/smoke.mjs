// Parcours de bout en bout dans un vrai navigateur (Chromium) : enrôlement MFA, connexion, navigation dans tous les écrans,
// création d'un partenaire + service, MO simulé, portail partenaire, bascule en arabe (RTL).
// Prérequis : application en profil dev avec MFA imposé : VAS_MFA_ENFORCED=true SPRING_PROFILES_ACTIVE=dev (comptes et opérateurs de démonstration).
//   cd frontend && npm i --no-save playwright-core
//   BASE=http://localhost:8080 ADMIN_USER=admin ADMIN_PASSWORD=... CHROMIUM=/opt/pw-browsers/chromium-1194/chrome-linux/chrome node e2e/smoke.mjs
import crypto from 'node:crypto';
import { chromium } from 'playwright-core';

const BASE = process.env.BASE || 'http://localhost:8080';
const U = process.env.ADMIN_USER || 'admin', P = process.env.ADMIN_PASSWORD;
const b32 = (s) => { const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; let bits = 0, v = 0; const o = []; for (const c of s) { v = (v << 5) | A.indexOf(c); bits += 5; if (bits >= 8) { o.push((v >>> (bits - 8)) & 255); bits -= 8; } } return Buffer.from(o); };
const totp = (secret) => { const c = Buffer.alloc(8); c.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000))); const h = crypto.createHmac('sha1', b32(secret)).update(c).digest(); const o = h[19] & 15; return String(((h.readUInt32BE(o) & 0x7fffffff) % 1e6)).padStart(6, '0'); };
const ok = (c, m) => { console.log((c ? 'OK   ' : 'ECHEC') + ' ' + m); if (!c) process.exitCode = 1; };

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM, args: ['--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
const jsErrors = [];
page.on('pageerror', (e) => jsErrors.push(String(e)));

async function login(pg, user, pass, code) {
  await pg.fill('input[autocomplete=username]', user);
  await pg.fill('input[autocomplete=current-password]', pass);
  if (code) await pg.fill('input[inputmode=numeric]', code);
  await pg.click('button.btn');
}

// 1. connexion SUPER_ADMIN sans MFA → page Sécurité imposée, enrôlement, reconnexion avec code
await page.goto(BASE + '/');
await login(page, U, P);
await page.waitForSelector('text=Activer le MFA');
ok(true, 'rôle sensible sans MFA : redirigé vers l\'enrôlement');
await page.click('button:text("Activer le MFA")');
const secret = (await page.locator('code').first().textContent()).trim();
await page.fill('input', totp(secret));
await page.click('button:text("Confirmer")');
await page.waitForSelector('text=reconnectez-vous');
await login(page, U, P, totp(secret));
await page.waitForSelector('.stats');
ok(true, 'connexion avec code TOTP → tableau de bord');

// 2. données via l'API (jeton)
const tok = (await (await fetch(BASE + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: U, password: P, code: totp(secret) }) })).json()).token;
const call = async (path, method = 'GET', body) => { const r = await fetch(BASE + path, { method, headers: { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' }, body: body && JSON.stringify(body) }); return r.status === 204 ? null : r.json(); };
const sc = await call('/admin/shortcodes', 'POST', { number: '85777', operatorCode: 'TT' });
const partner = await call('/admin/partners', 'POST', { name: 'Club Sportif', sharePercent: 30 });
const svc = await call('/admin/services', 'POST', { name: 'Vote Meilleur Joueur', type: 'VOTE', shortCodeId: sc.id, partnerId: partner.id });
await call(`/admin/services/${svc.id}/status/ACTIVE`, 'POST');
await call('/admin/keywords', 'POST', { serviceId: svc.id, word: 'VOTE' });
await call('/admin/users', 'POST', { username: 'club2', password: 'Club-pass-12345', roles: ['PARTNER'], partnerId: partner.id });
for (const [i, t] of ['VOTE A', 'VOTE A', 'VOTE B', 'vote أ'].entries()) {
  const r = await call(`/admin/sim/mo?connector=smppc_tt&from=9812345${i}&to=85777&content=${encodeURIComponent(t)}&id=e2e-${i}`, 'POST');
  ok(r.outcome === 'ROUTED', `MO « ${t} » → ${r.outcome}`);
}

// 3. navigation dans tous les écrans de l'admin
await page.click('nav button:text-is("Tableau de bord")');
const menu = await page.locator('nav button').allTextContents();
for (const name of menu) {
  await page.click(`nav button:text-is("${name}")`);
  await page.waitForTimeout(400);
  const err = await page.locator('.err').count();
  ok(err === 0, `écran « ${name} » sans erreur`);
}
await page.click('nav button:text-is("Messages")');
await page.waitForSelector('table tbody tr');
ok((await page.locator('table tbody tr').count()) >= 4, 'recherche de messages : au moins les 4 MT de ce test');
await page.screenshot({ path: 'e2e-admin.png' });

// 3b. modales, validation et confirmations : édition d'un opérateur
await page.click('nav button:text-is("Opérateurs")');
await page.waitForSelector('table tbody tr');
const rowTt = page.locator('table tbody tr', { hasText: 'TT' }).first();
await rowTt.locator('button:text-is("Modifier")').click();
await page.waitForSelector('[role=dialog]');
await page.fill('[role=dialog] input[type=number]', '0');
await page.click('[role=dialog] button:text-is("Enregistrer")');
ok(await page.locator('[role=dialog] .fielderr').count() > 0, 'validation : débit 0 refusé avec message lié au champ');
await page.fill('[role=dialog] input[type=number]', '40');
await page.click('[role=dialog] button:text-is("Enregistrer")');
await page.waitForSelector('[role=dialog]', { state: 'detached' });
await page.waitForFunction(() => [...document.querySelectorAll('table tbody tr')].some((r) => r.textContent.includes('TT') && r.textContent.includes('40')), null, { timeout: 8000 }).catch(() => {});
ok((await page.locator('table tbody tr', { hasText: 'TT' }).first().textContent()).includes('40'), 'modale : débit modifié à 40 et visible dans le tableau');
await page.locator('table tbody tr', { hasText: 'ORANGE' }).first().locator('button:text-is("Suspendre")').click();
await page.waitForSelector('[role=dialog]');
await page.click('[role=dialog] button:text-is("Annuler")');
ok((await page.locator('table tbody tr', { hasText: 'ORANGE' }).first().textContent()).includes('ACTIVE'), 'confirmation annulée : opérateur toujours actif');
await page.locator('table tbody tr', { hasText: 'ORANGE' }).first().locator('button:text-is("Suspendre")').click();
await page.click('[role=dialog] button:text-is("Confirmer")');
await page.waitForFunction(() => document.body.innerText.includes('SUSPENDED'));
ok(true, 'confirmation validée : opérateur suspendu');
await page.locator('table tbody tr', { hasText: 'ORANGE' }).first().locator('button:text-is("Activer")').click();
await page.waitForFunction(() => !document.body.innerText.includes('SUSPENDED'));

// 4. portail partenaire (autre contexte)
const ctx = await browser.newContext({ viewport: { width: 1100, height: 800 } });
const p2 = await ctx.newPage();
p2.on('pageerror', (e) => jsErrors.push(String(e)));
await p2.goto(BASE + '/');
await login(p2, 'club2', 'Club-pass-12345');
await p2.waitForSelector('h2:text("Club Sportif")');
ok((await p2.locator('nav button').count()) === 2, 'partenaire : menu limité (portail + sécurité)');
await p2.click('button:text("Résultats")');
await p2.waitForSelector('table');
ok((await p2.locator('table tbody tr').count()) === 3, 'portail : résultats par contenu (VOTE A, VOTE B, VOTE أ)');
await p2.screenshot({ path: 'e2e-portal.png' });
await p2.selectOption('header select', 'ar');
ok((await p2.evaluate(() => document.documentElement.dir)) === 'rtl', 'bascule arabe → dir=rtl');
await p2.screenshot({ path: 'e2e-portal-ar.png' });

ok(jsErrors.length === 0, 'aucune exception JavaScript' + (jsErrors.length ? ' : ' + jsErrors.join(' | ') : ''));
await browser.close();

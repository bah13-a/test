// Audit d'accessibilité automatisé (axe-core, WCAG 2.1 A/AA) de chaque écran, en français et en arabe, avec fenêtres modales ouvertes.
// Prérequis : application en profil dev (comptes de démonstration), `npm i --no-save playwright-core`.
//   BASE=http://localhost:8080 CHROMIUM=/opt/pw-browsers/chromium-1194/chrome-linux/chrome node e2e/a11y.mjs
import { createRequire } from 'node:module';
import { chromium } from 'playwright-core';

const require = createRequire(import.meta.url);
const BASE = process.env.BASE || 'http://localhost:8080';
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM, args: ['--no-sandbox'] });
const axePath = require.resolve('axe-core/axe.min.js');
let total = 0, audited = 0, passes = 0;
const seen = new Set();

async function audit(page, label) {
  await page.addScriptTag({ path: axePath });
  const res = await page.evaluate(async () => window.axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } }));
  for (const v of res.violations) {
    const key = `${v.id}|${label}`;
    if (seen.has(key)) continue;
    seen.add(key);
    total += v.nodes.length;
    console.log(`VIOLATION [${v.impact}] ${v.id} — ${label} : ${v.help} (${v.nodes.length} élément(s))\n   ${v.nodes.slice(0, 2).map((n) => n.target.join(' ')).join(' | ')}`);
  }
  audited++; passes += res.passes.length;
  return res.violations.length;
}

for (const lang of ['fr', 'ar']) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, bypassCSP: true }); // axe injecte son script : la CSP de l'application reste stricte
  const page = await ctx.newPage();
  await page.addInitScript((l) => localStorage.setItem('lang', l), lang);
  await page.goto(BASE + '/');
  await audit(page, `${lang}/connexion`);
  await page.fill('input[autocomplete=username]', 'admin');
  await page.fill('input[autocomplete=current-password]', 'Admin-dev-pass1');
  await page.click('button.btn');
  await page.waitForSelector('nav[aria-label]');
  const items = await page.locator('nav[aria-label] button').allTextContents();
  for (const name of items) {
    await page.click(`nav[aria-label] button:text-is("${name}")`);
    await page.waitForTimeout(700);
    await audit(page, `${lang}/${name}`);
    // ouvre la première fenêtre modale éventuelle (bouton Modifier / Édition) pour auditer le dialogue
    const edit = page.locator('main button.btn.sm').first();
    if (await edit.count()) {
      const txt = (await edit.textContent()) ?? '';
      if (/Modifier|تعديل|Nouvelle|نسخة|Mot|كلمة|Mots|الكلمات/.test(txt)) {
        await edit.click();
        if (await page.locator('[role=dialog]').count()) { await audit(page, `${lang}/${name} (modale)`); await page.keyboard.press('Escape'); }
      }
    }
  }
  await ctx.close();
}
console.log(`${audited} vues auditées (écrans et fenêtres modales, fr + ar), ${passes} contrôles axe réussis`);
console.log(total === 0 ? '\nACCESSIBILITÉ : aucune violation WCAG 2.1 A/AA détectée par axe-core' : `\nACCESSIBILITÉ : ${total} élément(s) en violation`);
await browser.close();
process.exit(total === 0 ? 0 : 1);

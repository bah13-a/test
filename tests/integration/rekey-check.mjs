// Vérifie que l'application (démarrée avec la nouvelle clé) déchiffre les numéros : /admin/messages doit répondre 200 avec des lignes.
import crypto from 'node:crypto';
import { execSync } from 'node:child_process';
const BASE = process.env.BASE || 'http://localhost:18090';
const secret = execSync(`psql -h /tmp -p 5433 -U postgres -d vas -Atc "select totp_secret from app_user where username='admin'"`).toString().trim();
const b32 = (s) => { const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; let bits = 0, v = 0; const o = []; for (const c of s) { v = (v << 5) | A.indexOf(c); bits += 5; if (bits >= 8) { o.push((v >>> (bits - 8)) & 255); bits -= 8; } } return Buffer.from(o); };
const c = Buffer.alloc(8); c.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
const h = crypto.createHmac('sha1', b32(secret)).update(c).digest(); const o = h[19] & 15;
const code = String(((h.readUInt32BE(o) & 0x7fffffff) % 1e6)).padStart(6, '0');
const r = await fetch(BASE + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'admin', password: process.env.ADMIN_PASSWORD || 'Admin-Real-Pass-42', code }) });
const tok = (await r.json()).token;
const m = await fetch(BASE + '/admin/messages?size=5', { headers: { Authorization: 'Bearer ' + tok } });
const rows = m.ok ? await m.json() : [];
const good = m.status === 200 && rows.length > 0 && rows.every((x) => /^\+216/.test(x.msisdn));
console.log(good ? `lecture OK (${rows.length} lignes, ex. ${rows[0].msisdn})` : `lecture KO (statut ${m.status})`);
process.exit(good ? 0 : 1);

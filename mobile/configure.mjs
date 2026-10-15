// Génère capacitor.config.json à partir du .env UNIQUE du projet (PUBLIC_HOSTNAME) : l'application native charge l'interface
// directement depuis https://PUBLIC_HOSTNAME (même origine que l'API : aucun CORS, mises à jour sans republier l'application).
// Variables : MOBILE_APP_ID (défaut tn.vas.backoffice), MOBILE_APP_NAME (défaut VAS), PUBLIC_HOSTNAME (obligatoire hors démonstration).
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const env = {};
const envFile = process.env.ENV_FILE || path.join(root, '.env');
if (fs.existsSync(envFile)) {
  for (const line of fs.readFileSync(envFile, 'utf8').split('\n')) {
    const m = line.match(/^([A-Z][A-Z0-9_]*)=('([^']*)'|(\S*))/);
    if (m) env[m[1]] = m[3] ?? m[4];
  }
}
const get = (k, d) => process.env[k] || env[k] || d;
const host = get('PUBLIC_HOSTNAME', 'vas.example.tn');
if (host.endsWith('example.tn')) console.warn('ATTENTION : PUBLIC_HOSTNAME non renseigné, application de démonstration');
const config = {
  appId: get('MOBILE_APP_ID', 'tn.vas.backoffice'),
  appName: get('MOBILE_APP_NAME', 'VAS'),
  webDir: 'www',
  server: { url: `https://${host}/`, cleartext: false, allowNavigation: [host] },
  android: { allowMixedContent: false, webContentsDebuggingEnabled: false },
  ios: { contentInset: 'automatic', limitsNavigationsToAppBoundDomains: true },
};
fs.writeFileSync(path.join(root, 'mobile', 'capacitor.config.json'), JSON.stringify(config, null, 2) + '\n');
console.log(`capacitor.config.json : ${config.appId} → https://${host}/`);

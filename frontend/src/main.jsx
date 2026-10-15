import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import { getLang, setLang } from './i18n.js';
import './styles.css';

setLang(getLang());
createRoot(document.getElementById('root')).render(<App />);

// Application installable (PWA) : service worker en production uniquement (jamais de cache des API, voir public/sw.js)
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('/sw.js').catch(() => {}));
}

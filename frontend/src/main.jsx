import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import { getLang, setLang } from './i18n.js';
import './styles.css';

setLang(getLang());
createRoot(document.getElementById('root')).render(<App />);

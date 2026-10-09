import { describe, expect, it } from 'vitest';
import { dict, t, setLang } from './i18n.js';
import { qs } from './api.js';

describe('i18n', () => {
  it('chaque langue couvre toutes les clés françaises', () => {
    for (const l of ['en', 'ar']) {
      const missing = Object.keys(dict.fr).filter((k) => !(k in dict[l]));
      expect(missing, `clés manquantes en ${l}`).toEqual([]);
    }
  });
  it('bascule de langue', () => { setLang('en'); expect(t('login')).toBe('Sign in'); setLang('fr'); expect(t('login')).toBe('Connexion'); });
  it('qs ignore les valeurs vides', () => { expect(qs({ a: 1, b: '', c: null })).toBe('a=1'); });
});

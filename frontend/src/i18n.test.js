import { describe, expect, it } from 'vitest';
import { dict, colDict, t, tc, setLang } from './i18n.js';
import { qs } from './api.js';

describe('i18n', () => {
  it('chaque langue couvre toutes les clés françaises', () => {
    for (const l of ['en', 'ar']) {
      const missing = Object.keys(dict.fr).filter((k) => !(k in dict[l]));
      expect(missing, `clés manquantes en ${l}`).toEqual([]);
    }
  });
  it('chaque colonne de tableau est traduite dans les trois langues', () => {
    for (const l of ['en', 'ar']) {
      const missing = Object.keys(colDict.fr).filter((k) => !(k in colDict[l]));
      expect(missing, `colonnes manquantes en ${l}`).toEqual([]);
    }
    setLang('ar'); expect(tc('status')).toBe('الحالة'); setLang('fr'); expect(tc('status')).toBe('Statut'); expect(tc('inconnue')).toBe('inconnue');
  });
  it('bascule de langue', () => { setLang('en'); expect(t('login')).toBe('Sign in'); setLang('fr'); expect(t('login')).toBe('Connexion'); });
  it('qs ignore les valeurs vides', () => { expect(qs({ a: 1, b: '', c: null })).toBe('a=1'); });
});

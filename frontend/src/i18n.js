// Interface en français, arabe (RTL) et anglais.
export const LANGS = { fr: 'Français', ar: 'العربية', en: 'English' };

const D = {
  fr: {
    app: 'Plateforme VAS', login: 'Connexion', username: 'Utilisateur', password: 'Mot de passe', totp: 'Code MFA (6 chiffres)', logout: 'Déconnexion',
    dashboard: 'Tableau de bord', operators: 'Opérateurs', shortcodes: 'Short codes', services: 'Services', tariffs: 'Tarifs', messages: 'Messages',
    ledger: 'Ledger', reconciliation: 'Rapprochement', rules: 'Listes noire/blanche', support: 'Support', users: 'Utilisateurs', apiClients: 'Clients API',
    partners: 'Partenaires', audit: 'Audit', security: 'Sécurité / MFA', portal: 'Mon portail', save: 'Enregistrer', create: 'Créer', search: 'Rechercher',
    export: 'Exporter', approve: 'Approuver', suspend: 'Suspendre', activate: 'Activer', delete: 'Supprimer', name: 'Nom', status: 'Statut', actions: 'Actions',
    empty: 'Aucune donnée', error: 'Erreur', ok: 'Opération réussie', loading: 'Chargement…', hours: 'Heures', pending: 'MT en attente', deliveryRate: 'Taux de livraison',
    mfaSetup: 'Activer le MFA', mfaSecret: 'Secret TOTP (à saisir dans votre application)', mfaConfirm: 'Confirmer', mfaEnabled: 'MFA actif', estimated: 'Montant estimé',
    reconciled: 'Montant rapproché', file: 'Fichier (CSV / XLSX)', from: 'Du', to: 'Au', simulate: 'Simuler', msisdn: 'MSISDN', keywords: 'Mots-clés', replies: 'Réponses',
    regulatory: 'Approbation réglementaire', forbidden: 'Droits insuffisants', mfaRequired: 'Code MFA requis', devBanner: 'ENVIRONNEMENT DE DÉMONSTRATION — données fictives, mocks actifs', devAccounts: 'Démo : admin / Admin-dev-pass1 · manager, noc, finance, finance2, support, auditor, club / Dev-pass-12345', mfaReconnect: 'MFA activé : reconnectez-vous avec votre code', badCreds: 'Identifiants invalides',
  },
  en: {
    app: 'VAS Platform', login: 'Sign in', username: 'Username', password: 'Password', totp: 'MFA code (6 digits)', logout: 'Sign out',
    dashboard: 'Dashboard', operators: 'Operators', shortcodes: 'Short codes', services: 'Services', tariffs: 'Tariffs', messages: 'Messages',
    ledger: 'Ledger', reconciliation: 'Reconciliation', rules: 'Black/white lists', support: 'Support', users: 'Users', apiClients: 'API clients',
    partners: 'Partners', audit: 'Audit', security: 'Security / MFA', portal: 'My portal', save: 'Save', create: 'Create', search: 'Search',
    export: 'Export', approve: 'Approve', suspend: 'Suspend', activate: 'Activate', delete: 'Delete', name: 'Name', status: 'Status', actions: 'Actions',
    empty: 'No data', error: 'Error', ok: 'Done', loading: 'Loading…', hours: 'Hours', pending: 'Pending MT', deliveryRate: 'Delivery rate',
    mfaSetup: 'Enable MFA', mfaSecret: 'TOTP secret (enter it in your authenticator app)', mfaConfirm: 'Confirm', mfaEnabled: 'MFA enabled', estimated: 'Estimated amount',
    reconciled: 'Reconciled amount', file: 'File (CSV / XLSX)', from: 'From', to: 'To', simulate: 'Simulate', msisdn: 'MSISDN', keywords: 'Keywords', replies: 'Replies',
    regulatory: 'Regulatory approval', forbidden: 'Insufficient rights', mfaRequired: 'MFA code required', devBanner: 'DEMO ENVIRONMENT — fake data, mocks enabled', devAccounts: 'Demo: admin / Admin-dev-pass1 · manager, noc, finance, finance2, support, auditor, club / Dev-pass-12345', mfaReconnect: 'MFA enabled: sign in again with your code', badCreds: 'Invalid credentials',
  },
  ar: {
    app: 'منصة القيمة المضافة', login: 'تسجيل الدخول', username: 'اسم المستخدم', password: 'كلمة المرور', totp: 'رمز التحقق (6 أرقام)', logout: 'خروج',
    dashboard: 'لوحة القيادة', operators: 'المشغلون', shortcodes: 'الأرقام القصيرة', services: 'الخدمات', tariffs: 'التعريفات', messages: 'الرسائل',
    ledger: 'دفتر الفوترة', reconciliation: 'المطابقة', rules: 'القوائم السوداء/البيضاء', support: 'الدعم', users: 'المستخدمون', apiClients: 'عملاء API',
    partners: 'الشركاء', audit: 'التدقيق', security: 'الأمان / التحقق', portal: 'بوابتي', save: 'حفظ', create: 'إنشاء', search: 'بحث',
    export: 'تصدير', approve: 'موافقة', suspend: 'تعليق', activate: 'تفعيل', delete: 'حذف', name: 'الاسم', status: 'الحالة', actions: 'إجراءات',
    empty: 'لا توجد بيانات', error: 'خطأ', ok: 'تمت العملية', loading: 'جار التحميل…', hours: 'ساعات', pending: 'رسائل معلقة', deliveryRate: 'نسبة التسليم',
    mfaSetup: 'تفعيل التحقق بخطوتين', mfaSecret: 'سر TOTP (أدخله في تطبيق المصادقة)', mfaConfirm: 'تأكيد', mfaEnabled: 'التحقق مفعل', estimated: 'المبلغ التقديري',
    reconciled: 'المبلغ المطابق', file: 'ملف (CSV / XLSX)', from: 'من', to: 'إلى', simulate: 'محاكاة', msisdn: 'رقم الهاتف', keywords: 'الكلمات المفتاحية', replies: 'الردود',
    regulatory: 'الموافقة التنظيمية', forbidden: 'صلاحيات غير كافية', mfaRequired: 'رمز التحقق مطلوب', devBanner: 'بيئة تجريبية — بيانات وهمية', devAccounts: 'تجريبي: admin / Admin-dev-pass1 · manager, noc, finance, finance2, support, auditor, club / Dev-pass-12345', mfaReconnect: 'تم تفعيل التحقق: سجل الدخول مجددا برمزك', badCreds: 'بيانات الدخول غير صحيحة',
  },
};

let lang = (typeof localStorage !== 'undefined' && localStorage.getItem('lang')) || 'fr';
export const getLang = () => lang;
export function setLang(l) {
  lang = l;
  try { localStorage.setItem('lang', l); } catch { /* stockage indisponible */ }
  if (typeof document !== 'undefined') { document.documentElement.lang = l; document.documentElement.dir = l === 'ar' ? 'rtl' : 'ltr'; }
}
export const t = (k) => D[lang]?.[k] ?? D.fr[k] ?? k;
export const dict = D;

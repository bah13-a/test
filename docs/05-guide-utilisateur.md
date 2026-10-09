# Guide utilisateur - back-office et portail partenaire

Langues : français, arabe (interface en RTL) et anglais - sélecteur en haut de page. L'interface est responsive (tablette/mobile).

## Connexion
Identifiant + mot de passe ; si le MFA est actif, le champ « Code MFA » apparaît (code à 6 chiffres de l'application d'authentification). 5 échecs verrouillent le compte 15 minutes.

## Menus par rôle
Le menu n'affiche que les écrans autorisés pour votre rôle (voir le guide administrateur). Un accès refusé est tracé dans l'audit.

## Tableau de bord
MO par issue (`ROUTED`, `UNKNOWN_KEYWORD`, `DUPLICATE`, `BLOCKED`...), MT par statut, backlog (`MT en attente`), taux de livraison, état des opérateurs et facturation par statut (rôles financiers). Période 1 h à 7 jours. Export PDF/XLSX/CSV.

## Messages
Recherche par ID, MSISDN, opérateur, short code, statut et période (200 résultats les plus récents). Les numéros et contenus sont **masqués** sauf pour SUPER_ADMIN et SUPPORT.

## Services, mots-clés, réponses
1. Créer le service (type, short code, partenaire, mode de consentement, langue, fenêtres, plafond par MSISDN).
2. Ajouter les mots-clés (variantes = plusieurs lignes) et, si besoin, les réponses par langue.
3. Créer et faire approuver le tarif (FINANCE ×2).
4. **Activer** le service. Les services réglementés restent bloqués jusqu'à l'approbation réglementaire.

## Tarifs
Prix facial TTC, % opérateur, % taxes, date d'effet ; le simulateur affiche taxes / part opérateur / partenaire / fournisseur. Un tarif approuvé est immuable : pour changer, créer une nouvelle version.

## Rapprochement
1. Choisir l'opérateur, le fichier CSV/XLSX du relevé, la période et le mapping des colonnes (noms d'en-tête).
2. Résumé par résultat : `MATCHED`, `AMOUNT_MISMATCH`, `STATUS_MISMATCH`, `MISSING_ON_PLATFORM`, `MISSING_ON_OPERATOR`.
3. Annoter ou corriger un écart (commentaire obligatoire, tracé dans l'audit) ; exporter les écarts en CSV/XLSX/PDF.

## Support
Historique de consentement d'un MSISDN pour un service (preuves exportables en CSV), désinscription manuelle (STOP) tracée.

## Portail partenaire
Rôle `PARTNER` : synthèse de **ses** services uniquement - volumes MO/MT, taux de livraison, résultats par contenu (ex. votes par choix), montants **estimés** (non encore facturés) et **rapprochés** (facturés/confirmés) clairement séparés, exports. Aucune donnée d'un autre partenaire n'est accessible.

## API partenaires
Voir `docs/openapi.json` ou `/swagger-ui.html`. Authentification `X-API-Key`. Exemple :

```bash
curl -X POST https://vas.example.tn/api/v1/messages \
  -H "X-API-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"to":"98123456","text":"Bonjour","serviceId":12,"clientRef":"cmd-4711"}'
# 202 {"id":"…","status":"PENDING",…}  (rejouer la même clientRef renvoie le même message)
```

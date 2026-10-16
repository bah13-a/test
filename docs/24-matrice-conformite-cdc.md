# Matrice de conformité au cahier des charges V1.0 (05/10/2026)

Réponse au §18, point 3. Version du 16/10/2026. Chaque ligne a été vérifiée dans le code. **130 exigences : 35 conformes, 91 partielles, 4 non couvertes ; couverture pondérée 62 %** (partiel = ½).

Les options du §2.2 (USSD, DCB, IVR, WhatsApp / RCS, BI, antifraude, application mobile, serveur SMPP) sont développées et ne sont pas comptées ici.

## §1-2 Contraintes et choix de gateway

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| 1.2-a | MUST | Paramètres opérateurs non codés en dur | Partiel | Délais de reconnexion et de remise en file figés dans le gabarit Jasmin ; address_range et timeouts absents ; liste des 3 opérateurs figée |
| 2.3-a | MUST | Client SMPP obligatoire ; serveur SMPP souhaité | Conforme | - |
| 2.3-b | MUST | Routage MO/MT par opérateur, short code, destination, service, règles | Partiel | Pas de règles de routage métier ; côté Jasmin, routes par utilisateur seulement |
| 2.3-c | MUST | Auto-reconnect, store-and-forward, retry contrôlé, files persistantes | Partiel | Retry à délai fixe ; un MT passe en échec si tous les liens tombent plus de 3 à 4 min |
| 2.3-d | MUST | Configuration automatisable, sauvegardable, versionnée | Partiel | Ni .env ni configuration Jasmin dans la sauvegarde ; reprovisionnement non idempotent |

## §5 SMPP v3.4

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| SMPP-001 | MUST | SMPP v3.4 conforme aux paramètres contractuels | Partiel | Dépend de SMPP-004 ; recette opérateur à faire |
| SMPP-002 | MUST | Bind TRX / TX / RX configurables | Partiel | Un seul mode par opérateur : couple TX + RX séparé impossible |
| SMPP-003 | MUST | submit_sm, deliver_sm, enquire_link, unbind, generic_nack | Conforme | - |
| SMPP-004 | MUST | Host, port, system_id, password, system_type, address_range, TON/NPI, bind, window, TPS, timeout | Partiel | address_range, timeouts, bind TON/NPI absents ; window non paramétrable dans Jasmin |
| SMPP-005 | MUST | Auto-reconnexion avec backoff et anti-boucle | Partiel | Délai fixe 10 s, sans backoff ni coupe-circuit |
| SMPP-006 | MUST | enquire_link configurable, détection de session dégradée | Partiel | Aucune métrique ni alerte sur l'état des liaisons |
| SMPP-007 | MUST | Throttling opérateur, adaptation du débit sans perte | Partiel | Limiteur par instance (débit doublé en HA) ; pas d'adaptation dynamique |
| SMPP-008 | SHOULD | Connexions multiples et liens de secours | Partiel | Secours avec les mêmes identifiants et port uniquement |
| SMPP-009 | MUST | Journal des événements SMPP, codes d'erreur, états de connexion | Partiel | Changements d'état non journalisés ni collectés |
| SMPP-010 | MUST | Secrets masqués dans logs et interfaces | Partiel | Le script de provisionnement affiche les mots de passe ; secret de callback dans les URL |
| 5.2-a | MUST | TON/NPI par connecteur et par route | Partiel | Par connecteur seulement |
| 5.2-b | MUST | Sender numérique, alphanumérique, short code | Partiel | Pas de contrôle des senders autorisés ni de TON adapté à l'alphanumérique |
| 5.2-c | MUST | GSM 7 bits, 8 bits / binaire, UCS-2 | Partiel | Envoi 8 bits / binaire impossible |
| 5.2-d | MUST | SMS longs UDH et/ou SAR configurable | Partiel | Choix UDH / SAR non exposé vers les opérateurs |
| 5.2-e | MUST | Nombre de segments exposé au moteur de coût | Partiel | Segments calculés mais non facturés (quantité 1) |
| 5.2-f | MUST | Normalisation MSISDN par opérateur et sens | Non couvert | Normalisation +216 codée en dur |
| 5.3 | MUST | Fiche de paramétrage : intégrer un opérateur sans code | Partiel | Environnement, contacts NOC, format DLR non paramétrables ; 4e opérateur = modification du code |

## §3 Cas d'usage

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| UC-01 | MUST | Vote SMS | Conforme | - |
| UC-02 | MUST | Quiz / jeu | Conforme | - |
| UC-03 | MUST | Contenu premium | Partiel | Par API : réglementaire, antifraude et plafonds non contrôlés |
| UC-04 | MUST | Abonnement récurrent | Partiel | Renouvellement global, pas par opérateur ; STOP par API sans trace de consentement |
| UC-05 | MUST | Campagne partenaire | Conforme | - |
| UC-06 | MUST | API partenaire jusqu'au callback | Conforme | - |
| UC-07 | MUST | Failover opérateur / lien | Partiel | Pas d'alerte sur la liaison ; file non conservée au-delà de 3 à 4 min |
| 3-conf | MUST | Activités réglementées activables / bloquables | Partiel | Pas de catégories ; contrôle absent sur API et SMPP ; un gestionnaire peut lever le blocage |

## §6 Flux MO, MT, DLR

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| MO-001 | MUST | Réception deliver_sm, identifiant unique | Conforme | - |
| MO-002 | MUST | Champs MO (dont encodage et message_id) | Partiel | Encodage non conservé |
| MO-003 | MUST | Doublons, fenêtre configurable | Conforme | - |
| MO-004 | MUST | Routage opérateur + short code + keyword + période + règles | Partiel | Même mot-clé sur deux campagnes d'un short code : choix arbitraire ; validité du short code non contrôlée |
| MO-005 | MUST | Keyword inconnu : réponse paramétrable ou rejet | Partiel | Rejet silencieux seulement |
| MO-006 | SHOULD | Webhook partenaire après validation | Conforme | - |
| MO-007 | MUST | Accusé applicatif et retry | Conforme | - |
| MT-001 | MUST | Corrélation ID interne / message_id SMSC | Partiel | Identifiant Jasmin conservé, pas celui du SMSC |
| MT-002 | MUST | Priorités configurables | Partiel | Priorités des MT du moteur codées en dur |
| MT-003 | MUST | Queue persistante, non-perte | Conforme | - |
| MT-004 | MUST | Retry configurable par code erreur | Partiel | Pas de table code d'erreur → retry / échec |
| MT-005 | MUST | Validité configurable | Partiel | 24 h codées en dur (sauf serveur SMPP) |
| MT-006 | SHOULD | Envoi programmé | Conforme | - |
| MT-007 | MUST | Débit par opérateur, compte, service, partenaire | Partiel | Compte : quota de requêtes et non débit d'envoi ; limiteur par instance |
| DLR-a | MUST | Réception et interprétation des DLR | Conforme | - |
| DLR-b | MUST | Statuts normalisés (dont ACCEPTED) | Partiel | ACCEPTED absent |
| DLR-c | MUST | Statut brut conservé | Conforme | - |
| DLR-d | MUST | Horodatage de chaque transition | Conforme | - |
| DLR-e | MUST | Callback DLR signé | Conforme | - |
| DLR-f | MUST | Retry des callbacks avec DLQ | Partiel | Pas d'écran ni d'API pour consulter / rejouer la DLQ |
| DLR-g | MUST | Règle de facturation sur DLR par service et opérateur | Partiel | Par opérateur seulement |

## §7 Moteur VAS

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| 7.1-op | MUST | Opérateur : contacts, environnement, connecteurs… | Partiel | Contacts et environnement absents |
| 7.1-sc | MUST | Short code : type, validité, TPS, statut | Partiel | Type et TPS absents ; validité non appliquée |
| 7.1-svc | MUST | Service : description, catégorie, dates, SLA… | Partiel | Description, catégorie, SLA absents |
| 7.1-kw | MUST | Keyword : variantes, casse, réponse par défaut | Partiel | Variantes, casse et réponse par défaut absentes |
| 7.1-tar | MUST | Tarif : opérateur, short code, unité, frais, dates | Partiel | Unité, frais, date de fin absents |
| 7.1-scn | MUST | Scénario configurable | Non couvert | Scénarios codés par type de service |
| 7.1-sub | MUST | Abonnement complet | Partiel | Source du consentement et périodicité non portées |
| 7.2-a | MUST | Activation / désactivation immédiate | Conforme | - |
| 7.2-b | MUST | Périodes de campagne avec timezone | Partiel | Pas de fuseau horaire configurable |
| 7.2-c | MUST | Réponses par opérateur, langue, résultat, statut | Partiel | Par langue seulement |
| 7.2-d | MUST | FR / AR / EN contenus et interfaces | Partiel | Questions, options et contenus monolingues |
| 7.2-e | MUST | STOP / DESABO / AIDE configurables par service | Partiel | Mots globaux, seuls les textes sont configurables |
| 7.2-f | MUST | Actions max par MSISDN, campagne, période | Partiel | Pas de période |
| 7.2-g | MUST | Blacklist / whitelist | Conforme | - |
| 7.2-h | MUST | Traçabilité des modifications de tarifs et règles | Partiel | Valeurs avant modification non journalisées |
| 7.3-a | MUST | Schémas de consentement par service | Partiel | Pas de mode web ni de confirmation SMS distincte |
| 7.3-b | MUST | Preuve : source, canal, version des conditions | Partiel | Version des conditions figée à v1 |
| 7.3-c | MUST | Confirmation post-activation configurée | Partiel | Activation API ignore les textes par langue |
| 7.3-d | MUST | Désinscription immédiate et horodatée | Partiel | STOP par API / support sans preuve ni SMS |
| 7.3-e | MUST | Blocage des renouvellements après STOP | Conforme | - |
| 7.3-f | MUST | Export d'historique des consentements | Partiel | Par numéro et service seulement, CSV seulement |

## §8 Billing et rapprochement

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| 8.1 | MUST | Ledger immuable / auditable, champs requis | Partiel | Keyword, frais, dates de facturation / rapprochement / correction absents ; pas d'immuabilité en base |
| 8.2-a | MUST | Tarifs versionnés, pas de rétroactivité | Conforme | - |
| 8.2-b | MUST | Tarif par opérateur, short code, service, type | Partiel | Par service et type seulement |
| 8.2-c | MUST | Prix facial, partage, règles configurables | Partiel | Part partenaire hors tarif, formule fixe |
| 8.2-d | MUST | Simulation avant activation | Partiel | Simulation libre, pas d'un tarif brouillon |
| 8.2-e | MUST | Approbation des changements tarifaires | Conforme | - |
| 8.3-a | MUST | Import CSV / XLSX ou API | Partiel | Pas d'import par API |
| 8.3-b | MUST | Mapping configurable | Conforme | - |
| 8.3-c | MUST | Rapprochement par id, période, service, volumes, montants | Partiel | Pas de contrôle de l'opérateur ; ni service ni volumes |
| 8.3-d | MUST | Écarts : volume, statut, montant, duplicata, absence | Partiel | Volume absent ; statut, duplicata, absence partiels |
| 8.3-e | MUST | Dashboard de réconciliation et export | Partiel | Pas de liste des lots ni de totaux |
| 8.3-f | MUST | Journal de correction et commentaire | Partiel | Commentaire écrasé ; correction hors automate de statuts |

## §9 API

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| 9.1 | MUST | Endpoints logiques (dont /webhooks/dlr, /health restreint) | Partiel | Modèle de webhook DLR non documenté dans l'OpenAPI ; /health public |
| 9.2-a | MUST | HTTPS obligatoire | Partiel | Callbacks internes en HTTP |
| 9.2-b | MUST | OAuth2 / API key, pas de mot de passe en clair | Conforme | - |
| 9.2-c | MUST | Scopes et quotas par client | Partiel | Pas de quota de volume journalier / mensuel |
| 9.2-d | MUST | Rate limiting | Conforme | - |
| 9.2-e | MUST | Signature HMAC des webhooks | Partiel | Secret partenaire facultatif ; callbacks Jasmin sans HMAC |
| 9.2-f | MUST | Protection anti-rejeu | Partiel | Callbacks entrants sans horodatage ni nonce |
| 9.2-g | MUST | Journal des accès API | Conforme | - |
| 9.2-h | MUST | OpenAPI livrée | Partiel | Sans schémas de sécurité ni codes d'erreur |

## §10 Back-office et portail

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| 10.1-a | MUST | Dashboard : binds, TPS, files, erreurs, dispo | Partiel | État réel des binds, TPS réel, erreurs SMPP absents |
| 10.1-b | MUST | Opérateurs / connecteurs sans exposition des secrets | Partiel | Connecteurs non gérés dans le back-office |
| 10.1-c | MUST | Clients, services, short codes, keywords, tarifs, campagnes | Conforme | - |
| 10.1-d | MUST | Recherche de message | Partiel | MT seulement, pas les MO |
| 10.1-e | MUST | Console de réconciliation | Conforme | - |
| 10.1-f | MUST | Exports CSV / XLSX / PDF | Conforme | - |
| 10.1-g | MUST | Utilisateurs, rôles, MFA, audit | Conforme | - |
| 10.2 | MUST | 7 rôles minimum | Conforme | - |
| 10.3 | SHOULD | Dashboard partenaire | Partiel | Pas de choix de période ; DLR non distingués |

## §11 Sécurité et conformité

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| 11.1-a | MUST | Segmentation réseau | Partiel | Un seul réseau Docker |
| 11.1-b | MUST | Firewall, allowlist, pas d'admin public | Partiel | Allowlist admin vide par défaut ; Swagger public |
| 11.1-c | MUST | VPN / IPsec opérateurs | Non couvert | Aucune configuration ni procédure |
| 11.1-d | MUST | SSH par clés / bastion | Partiel | Mentionné, sans procédure |
| 11.1-e | MUST | Durcissement OS et patchs documentés | Partiel | Pas de guide de durcissement |
| 11.1-f | MUST | Coffre de secrets, rotation | Partiel | Pas de coffre ; rotation manuelle |
| 11.1-g | MUST | Sauvegardes chiffrées et restauration testée | Partiel | Pas d'archivage WAL ; test sur petit volume |
| 11.2-a | MUST | RBAC, MFA, mots de passe, sessions | Conforme | - |
| 11.2-b | MUST | Protection OWASP | Partiel | Pas de DAST ni de test d'intrusion |
| 11.2-c | MUST | TLS web / API | Conforme | - |
| 11.2-d | MUST | Chiffrement au repos des données sensibles | Partiel | Secrets TOTP et webhook en clair |
| 11.2-e | MUST | Masquage MSISDN selon rôle | Conforme | - |
| 11.2-f | MUST | Aucun secret dans Git | Conforme | - |
| 11.2-g | MUST | Scan de dépendances bloquant | Partiel | Scans non bloquants en CI |
| 11.3-a | MUST | Journal d'audit non modifiable et complet | Partiel | Échecs de connexion et plusieurs exports non audités |
| 11.3-b | MUST | Versionnage des paramètres critiques | Partiel | Tarifs seulement |
| 11.3-c | MUST | Preuve de consentement et historique d'un service | Partiel | Historique d'un service non extractible |
| 11.3-d | MUST | Suspension immédiate service / short code | Conforme | - |
| 11.3-e | MUST | Rapport de sécurité et d'architecture | Partiel | Incohérences à corriger ; intrusion non faite |

## §12 Performance, HA, supervision

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| 12.1 | MUST | 50 SMS/s par opérateur, 200 agrégés, P95, RPO, RTO | Partiel | 200 SMS/s non mesuré ; RPO 15 min non tenu sans WAL |
| 12.2 | MUST | Pas de SPOF, bascule testée, PRA | Partiel | etcd, répartiteurs et Jasmin non redondés ; bascule non exécutée |
| 12.3 | MUST | Monitoring binds, TPS, erreurs SMPP, files, DLQ, infra, alertes | Partiel | Seule l'application est supervisée ; pas de métriques SMPP ni d'infrastructure |

## §13-16 Exploitation, tests, livrables, maintenance

| ID | Prio | Exigence | Statut | Écart |
|---|---|---|---|---|
| 13.1 | MUST | DEV, SIT, PREPROD, PROD | Partiel | SIT et PREPROD non définis |
| 13.2 | MUST | Tags, pipelines, rollback, release notes | Partiel | Pas de tags, de déploiement, de rollback ni de notes de version |
| 13.3 | MUST | Simulateur SMPP complet | Partiel | Pas de timeouts ni d'erreurs submit_sm simulés |
| 14.1 | MUST | Tests unitaires, intégration, charge, panne, sécurité, UAT | Partiel | Pannes base / broker / Jasmin et callback en erreur non testés |
| 15.1 | MUST | Livrables obligatoires | Partiel | Schéma réseau détaillé, supports de formation, rapports charge / intrusion |
| 16 | MUST | Rapport mensuel de maintenance | Non couvert | Aucun modèle ni outil |

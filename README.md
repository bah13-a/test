# Plateforme VAS / SMS Premium - SMPP v3.4 (Tunisie)

Implémentation de la V1 du *Cahier des charges technique & fonctionnel* (TT, Orange, Ooredoo) avec la stack du CDC §4.2 / Annexe E :
**Linux + Jasmin SMS Gateway + moteur VAS Java 21 / Spring Boot + PostgreSQL + Redis + RabbitMQ + API REST (OpenAPI) + back-office + Prometheus/Grafana**.

```
Opérateurs (SMPP v3.4, VPN) ⇄ Jasmin ⇄ HTTP callbacks ⇄ [ moteur VAS ] ⇄ PostgreSQL
                                   ▲                        │  ├─ RabbitMQ (files MT par priorité + DLQ)
                  POST /send (HTTP)└────────────────────────┘  ├─ Redis (quotas API)
                                                              └─ API REST /api/v1, back-office /admin, /actuator/prometheus
```

La gateway ne contient aucune logique métier : `SmsGateway` (interface) est remplaçable (Kannel, autre) sans toucher au moteur.

## Démarrage

```bash
cp .env.example .env            # renseigner TOUS les secrets
docker compose up -d --build    # postgres, redis, rabbitmq, jasmin, app, prometheus, grafana
mvn verify                      # tests (H2 + simulateur, sans Docker)
```
- API : `http://localhost:8080/swagger-ui.html` (OpenAPI : `/v3/api-docs`) - back-office : `http://localhost:8080/`
- Grafana : `:3000` (dashboard « VAS - Opérations »), Prometheus : `:9090`
- Jasmin : adapter `infra/jasmin/bootstrap.jcli` aux paramètres de chaque opérateur (Annexe B) - gabarit à valider sur l'environnement de test.
- Dev sans Jasmin : `VAS_SIMULATOR=true VAS_QUEUE=memory VAS_RATE_LIMIT=memory` (simulateur de gateway + endpoints `/admin/sim/*`).
- Compte admin : `ADMIN_PASSWORD_HASH` au format `{bcrypt}$2a$...`. Un compte `prometheus` (rôle NOC) est à ajouter pour le scraping.

## Couverture du CDC

| Exigence | Réalisation |
|---|---|
| MO-001..007 | `/callbacks/mo` (Jasmin) → `MoService` : dédoublonnage (message_id + fenêtre), routage opérateur+short code+keyword, keyword inconnu = rejet silencieux, HTTP 500 → Jasmin rejoue |
| MT-001..007 | `MtService` (persist) → RabbitMQ (3 priorités, DLQ) → `MtDispatcher` (rate gate par opérateur, validité, retry) ; `MtSweeper` reprend tout MT resté PENDING : **zéro perte** |
| DLR §6.3 | `DlrMapper`, statut brut conservé + historique horodaté, idempotent (statut final jamais écrasé), règle de facturation `ON_DELIVERED`/`ON_SUBMITTED` par opérateur |
| Encodage §5.2 | GSM-7 / UCS-2 (arabe), segments (160/153, 70/67), normalisation MSISDN +216 |
| Consentement §7.3 | opt-in simple, double opt-in (OUI), activation API ; preuves dans `consent_record` ; STOP/DESABO/ARRET immédiat, renouvellement bloqué |
| Services §7.2 | statut modifiable sans redéploiement, fenêtres d'ouverture, limite par MSISDN, services réglementés **bloqués tant que non approuvés** |
| Billing §8 | ledger à `event_id` unique (idempotence), tarifs versionnés non rétroactifs avec approbation 4-yeux, partage taxes/opérateur/partenaire/fournisseur, rapprochement CSV (écarts montant, statut, absences, doublons) |
| API §9 | `/api/v1` : messages (idempotent via `clientRef`), services, reports, subscriptions, health ; clés hachées SHA-256, scopes, quotas Redis, isolation par partenaire ; webhooks DLR signés HMAC-SHA256 + retry + DLQ |
| Back-office §10 | `/admin` : 7 rôles RBAC, audit, exports, MSISDN masqués ; UI minimale en lecture (`static/`) |
| Monitoring §12.3 | `/actuator/prometheus`, compteurs `vas_mo_total`, `vas_mt_total`, jauge `vas_mt_pending`, alertes `infra/prometheus/alerts.yml` |
| Simulateur §13.3 | `SimulatorGateway` + `/admin/sim` (MO, DLR, coupure de lien) ; tests de non-régression `FlowTests` |

## Limites connues (honnêteté de V1)
- **Non testé contre un vrai SMSC / Jasmin** : seuls le simulateur et H2 sont exercés ; le provisioning Jasmin est un gabarit.
- MFA, rotation de secrets, PostgreSQL HA, PRA, logs centralisés, import XLSX, i18n des écrans, portail partenaire complet et tests de charge restent à réaliser (voir `docs/chiffrage`).
- Le routage MT par préfixe est volontairement non pré-rempli (portabilité des numéros) : l'opérateur est donné par le service/short code ou par l'appelant.
- Le dédoublonnage de MO concurrents repose sur la contrainte unique : un rejeu simultané peut produire un HTTP 500 puis être traité comme doublon au rejeu Jasmin.

## Chiffrage
`docs/Chiffrage_detaille_VAS_SMPP_Tunisie_V1.pdf` (généré par `docs/chiffrage/generate_chiffrage.py`, hypothèses modifiables).

# Matrice des composants et licences (CDC §15.1)

Aucun composant à licence commerciale obligatoire : coût de licences = 0 TND.

| Composant | Version | Licence | Remarque |
|---|---|---|---|
| Jasmin SMS Gateway | 0.10 | Apache-2.0 | gateway SMPP ; remplaçable (interface `SmsGateway`) |
| Java (Temurin) | 21 | GPLv2 + Classpath Exception | |
| Spring Boot / Security / Data | 3.3.4 | Apache-2.0 | |
| PostgreSQL | 16 | PostgreSQL License | |
| PostgreSQL JDBC | 42.7.x | BSD-2-Clause | |
| Flyway Community | 10.x | Apache-2.0 | |
| RabbitMQ | 3.13 | MPL-2.0 | |
| Redis | **7.2.x** | BSD-3-Clause | ne pas dépasser 7.2 (7.4+ : licences RSALv2/SSPL) ; alternative : Valkey (BSD-3) |
| jsmpp | 3.0.0 | Apache-2.0 | simulateur SMPP / tests |
| Apache POI | 5.3.0 | Apache-2.0 | import/export XLSX |
| OpenPDF | 2.0.3 | LGPL-2.1 / MPL-2.0 | export PDF |
| springdoc-openapi | 2.6.0 | Apache-2.0 | |
| Micrometer, Logstash encoder | - / 8.0 | Apache-2.0 | |
| Lombok | - | MIT | compilation |
| H2 | 2.x | MPL-2.0 / EPL-1.0 | profil `dev` / tests uniquement |
| React, Vite, Vitest | 19 / 7 / 3 | MIT | |
| Prometheus, etcd, Patroni (Spilo) | - | Apache-2.0 / MIT | |
| Grafana, Loki, Promtail | 11 / 3 | **AGPL-3.0** | usage non modifié en supervision : pas d'obligation de publication ; ne pas modifier/redistribuer sans analyse. Alternative : Prometheus + OpenSearch |
| HAProxy | 3.0 | GPL-2.0 / LGPL | usage en l'état |
| Docker images de base | - | selon image | |

Mise à jour de cette matrice à chaque changement de version (livrable de maintenance).

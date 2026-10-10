-- CQRS : modèles de lecture alimentés par les événements du côté commande (jamais écrits par les contrôleurs ni les requêtes).
-- rm_traffic_hourly : MO par issue et MT par statut courant, par heure de création, opérateur et service (service_id 0 = aucun).
CREATE TABLE rm_traffic_hourly (
  hour_ts TIMESTAMP WITH TIME ZONE NOT NULL,
  operator_id BIGINT NOT NULL,
  service_id BIGINT NOT NULL,
  kind VARCHAR(2) NOT NULL,
  state VARCHAR(20) NOT NULL,
  n BIGINT NOT NULL,
  PRIMARY KEY (hour_ts, operator_id, service_id, kind, state)
);
CREATE INDEX ix_rm_traffic_service ON rm_traffic_hourly (service_id, kind, state);
-- rm_ledger_hourly : événements de facturation par statut, par heure de création et service.
CREATE TABLE rm_ledger_hourly (
  hour_ts TIMESTAMP WITH TIME ZONE NOT NULL,
  service_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  events BIGINT NOT NULL,
  gross DECIMAL(16,3) NOT NULL,
  partner_share DECIMAL(16,3) NOT NULL,
  provider_share DECIMAL(16,3) NOT NULL,
  PRIMARY KEY (hour_ts, service_id, status)
);
CREATE INDEX ix_rm_ledger_service ON rm_ledger_hourly (service_id, status);

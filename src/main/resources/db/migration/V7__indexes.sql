-- Index des chemins chauds, déduits des requêtes réelles (recherche de messages, tableau de bord, balayeurs, dédoublonnage MO,
-- rapprochement, grand livre). Mesures avant/après sur 2 M de lignes : docs/15-performance-base.md
CREATE INDEX ix_mt_msisdn_created ON mt_message (msisdn, created_at);
CREATE INDEX ix_mt_created ON mt_message (created_at);
CREATE INDEX ix_mt_status_updated ON mt_message (status, updated_at);
CREATE INDEX ix_mt_operator_created ON mt_message (operator_id, created_at);
CREATE INDEX ix_mt_service_status ON mt_message (service_id, status);
CREATE INDEX ix_mt_apiclient_ref ON mt_message (api_client_id, client_ref);
CREATE INDEX ix_hist_mt ON mt_status_history (mt_id);
CREATE INDEX ix_mo_received ON mo_message (received_at);
CREATE INDEX ix_mo_service_outcome ON mo_message (service_id, outcome);
CREATE INDEX ix_mo_dedup_content ON mo_message (operator_id, msisdn, short_code, received_at);
CREATE INDEX ix_mo_service_msisdn ON mo_message (service_id, msisdn, outcome);
CREATE INDEX ix_ledger_created ON ledger_event (created_at);
CREATE INDEX ix_ledger_status_created ON ledger_event (billing_status, created_at);
CREATE INDEX ix_ledger_service_status ON ledger_event (service_id, billing_status);
CREATE INDEX ix_ledger_operator_created ON ledger_event (operator_id, created_at);
CREATE INDEX ix_audit_at ON audit_log (at);
CREATE INDEX ix_consent_msisdn_service ON consent_record (msisdn, service_id);
CREATE INDEX ix_sub_renewal ON subscription (status, next_renewal_at);
CREATE INDEX ix_webhook_due ON webhook_outbox (status, next_attempt_at);
CREATE INDEX ix_recon_batch ON recon_item (batch_id, result);
CREATE INDEX ix_rule_service ON msisdn_rule (service_id);

-- Serveur SMPP v3.4 pour clients externes : un client API peut aussi se connecter en SMPP (system_id + mot de passe haché),
-- avec liste d'adresses IP autorisées, nombre maximal de connexions et réception optionnelle des MO de ses services.
ALTER TABLE api_client ADD COLUMN smpp_system_id VARCHAR(16);
ALTER TABLE api_client ADD COLUMN smpp_password_hash VARCHAR(100);
ALTER TABLE api_client ADD COLUMN smpp_allowed_ips VARCHAR(300);
ALTER TABLE api_client ADD COLUMN smpp_max_binds INT DEFAULT 2 NOT NULL;
ALTER TABLE api_client ADD COLUMN smpp_mo BOOLEAN DEFAULT FALSE NOT NULL;
CREATE UNIQUE INDEX uq_api_client_smpp ON api_client (smpp_system_id);
-- Accusé de livraison demandé (registered_delivery) par un client SMPP et état de sa remise (rejoué à la reconnexion).
ALTER TABLE mt_message ADD COLUMN smpp_dlr BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE mt_message ADD COLUMN smpp_dlr_sent BOOLEAN DEFAULT FALSE NOT NULL;
CREATE INDEX ix_mt_smpp_dlr_pending ON mt_message (api_client_id, smpp_dlr, smpp_dlr_sent);

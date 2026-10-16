-- registered_delivery SMPP v3.4 (bits 0-1) : 1 = accusé sur succès et échec, 2 = accusé sur échec seulement.
ALTER TABLE mt_message ADD COLUMN smpp_dlr_mode SMALLINT DEFAULT 0 NOT NULL;

-- Préfixes MSISDN volontairement vides : à renseigner après validation (portabilité des numéros incluse).
INSERT INTO operator (code, name, msisdn_prefixes, jasmin_connector, dlr_billing_rule, max_tps) VALUES
 ('TT',  'Tunisie Telecom', '', 'smppc_tt', 'ON_DELIVERED', 50),
 ('ORANGE', 'Orange Tunisie', '', 'smppc_orange', 'ON_DELIVERED', 50),
 ('OOREDOO', 'Ooredoo Tunisie', '', 'smppc_ooredoo', 'ON_DELIVERED', 50);

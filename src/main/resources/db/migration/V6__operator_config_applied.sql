-- Première synchronisation de la configuration (vas.operators) : appliquée une fois, puis le back-office fait foi (sauf mode overwrite)
ALTER TABLE operator ADD COLUMN config_applied BOOLEAN NOT NULL DEFAULT FALSE;

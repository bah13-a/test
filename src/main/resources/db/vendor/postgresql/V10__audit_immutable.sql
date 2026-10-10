-- Journal d'audit non modifiable au niveau base (CDC §11.3) : UPDATE interdit ; DELETE autorisé uniquement pour la purge de conservation
-- (RetentionJob positionne vas.audit_purge = 'on' dans sa transaction). PostgreSQL uniquement (le dossier migration/postgresql n'est lu que sur PG).
CREATE FUNCTION audit_log_guard() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION 'audit_log est en ajout seul : UPDATE interdit';
  END IF;
  IF TG_OP = 'DELETE' AND coalesce(current_setting('vas.audit_purge', true), '') <> 'on' THEN
    RAISE EXCEPTION 'audit_log est en ajout seul : DELETE interdit hors purge de conservation';
  END IF;
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_log_guard BEFORE UPDATE OR DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION audit_log_guard();
CREATE FUNCTION audit_log_no_truncate() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_log est en ajout seul : TRUNCATE interdit';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_log_no_truncate BEFORE TRUNCATE ON audit_log FOR EACH STATEMENT EXECUTE FUNCTION audit_log_no_truncate();

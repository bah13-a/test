#!/usr/bin/env python3
"""Contrôle complet du fichier .env UNIQUE avant le démarrage : présence, formats, cohérence et fichiers référencés.

  python3 tools/check-env.py [--file .env] [--strict] [--sample]

Code de sortie 1 s'il reste une erreur (avec --strict : aussi pour les avertissements). --sample : ne pas exiger les valeurs externes (sert à
vérifier la structure du modèle .env.example). Les règles reprennent celles de ProductionGuard (démarrage refusé sinon) et ajoutent les contrôles
d'intégration : un opérateur incomplet, un fichier de données absent ou un hash incohérent sont détectés ici, pas en production."""
import csv, hashlib, json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OPS = ["TT", "ORANGE", "OOREDOO"]
errors, warnings = [], []


def parse(path):
    env = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line: continue
        k, v = line.split("=", 1)
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", k): continue
        q = re.match(r"'([^']*)'", v)
        if q: v = q.group(1)
        else:
            v = re.sub(r"\s+#.*$", "", v).strip()
            if len(v) >= 2 and v[0] == v[-1] == '"': v = v[1:-1]
        env[k] = v
    return env


def err(m): errors.append(m)
def warn(m): warnings.append(m)


def main():
    path = ROOT / ".env"
    if "--file" in sys.argv: path = Path(sys.argv[sys.argv.index("--file") + 1])
    sample = "--sample" in sys.argv
    if not path.exists(): sys.exit(f"{path} introuvable : python3 tools/init-env.py")
    env = parse(path)
    tmpl = parse(ROOT / ".env.example")
    g = lambda k, d="": env.get(k, d)

    for k in tmpl:
        if k not in env: err(f"{k} : variable absente (présente dans .env.example)")
    for k in env:
        if k not in tmpl: warn(f"{k} : variable inconnue (absente de .env.example), ignorée")

    weak = re.compile(r"change[-_]?me|changeme|example|password|dev-|^dev|secret-test", re.I)
    def secret(k, minlen):
        v = g(k)
        if not v: return err(f"{k} : obligatoire") if not sample else None
        if len(v) < minlen: err(f"{k} : {minlen} caractères minimum")
        elif weak.search(v) and not sample: err(f"{k} : valeur faible ou d'exemple")
    for k, n in (("TOKEN_SECRET", 32), ("DATA_KEY", 32), ("CALLBACK_SECRET", 24), ("DB_PASSWORD", 12), ("RABBIT_PASSWORD", 12), ("REDIS_PASSWORD", 12), ("GRAFANA_PASSWORD", 12), ("PROMETHEUS_PASSWORD", 12), ("JCLI_PASSWORD", 12)):
        secret(k, n)
    jp = g("JASMIN_PASSWORD")
    if jp and not re.fullmatch(r"[A-Za-z0-9_-]{12,16}", jp): err("JASMIN_PASSWORD : 12 à 16 caractères parmi A-Za-z0-9_- (limite de Jasmin)")
    elif not jp and not sample: err("JASMIN_PASSWORD : obligatoire")
    if g("JCLI_PASSWORD") and hashlib.md5(g("JCLI_PASSWORD").encode()).hexdigest() != g("JCLI_PASSWORD_MD5").lower():
        err("JCLI_PASSWORD_MD5 : ne correspond pas à md5(JCLI_PASSWORD) (relancer init-env.py --fill après avoir vidé la valeur)")
    for k in ("ADMIN_PASSWORD_HASH", "PROMETHEUS_PASSWORD_HASH"):
        v = g(k)
        if not v:
            if not sample: err(f"{k} : obligatoire")
        elif not re.fullmatch(r"\{bcrypt\}\$2[aby]\$\d\d\$.{53}", v): err(f"{k} : format attendu {{bcrypt}}$2a$10$... (valeur entre apostrophes dans le .env)")
    if g("DATA_KEY") == g("TOKEN_SECRET") and g("DATA_KEY"): err("DATA_KEY et TOKEN_SECRET doivent être différentes")

    url = g("VAS_PUBLIC_BASE_URL")
    m = re.match(r"https?://([^:/]+)", url)
    if not m: err("VAS_PUBLIC_BASE_URL : URL http(s) attendue")
    elif "." not in m.group(1) and m.group(1) != "localhost": err("VAS_PUBLIC_BASE_URL : Jasmin refuse un nom d'hôte sans point (utiliser app.vas.internal)")
    elif m.group(1) == "localhost": err("VAS_PUBLIC_BASE_URL : localhost interdit en production")
    for k, p in (("DB_URL", r"jdbc:postgresql://.+"), ("READ_DB_URL", r"(jdbc:postgresql://.+)?")):
        if not re.fullmatch(p, g(k)): err(f"{k} : jdbc:postgresql://hôte:5432/base attendu")
    if g("OPERATORS_SYNC") not in ("create-only", "overwrite"): err("OPERATORS_SYNC : create-only ou overwrite")
    for k, lo, hi in (("SESSION_TTL_MINUTES", 5, 480), ("PASSWORD_MIN_LENGTH", 12, 128), ("AUTH_THROTTLE_PER_MINUTE", 1, 10000), ("MT_MAX_ATTEMPTS", 1, 50), ("MT_RETRY_BACKOFF_SECONDS", 1, 86400),
                      ("MO_DEDUP_SECONDS", 0, 3600), ("DLR_TIMEOUT_HOURS", 1, 720), ("SUBSCRIPTION_RENEWAL_DAYS", 1, 365), ("SUBSCRIPTION_RETRY_DAYS", 1, 30), ("SUBSCRIPTION_MAX_FAILURES", 1, 20),
                      ("SUBSCRIPTION_GUARD_DAYS", 0, 30), ("DB_POOL_MAX", 1, 500), ("DB_POOL_MIN", 0, 500), ("POSTGRES_MAX_CONNECTIONS", 20, 5000), ("SERVER_MAX_THREADS", 10, 2000),
                      ("RETENTION_MESSAGES_DAYS", 0, 36500), ("RETENTION_CONSENT_DAYS", 0, 36500), ("RETENTION_AUDIT_DAYS", 0, 36500), ("RETENTION_WEBHOOK_DAYS", 0, 36500),
                      ("BACKUP_RETENTION_DAYS", 1, 3650), ("HTTP_PORT", 1, 65535), ("HTTPS_PORT", 1, 65535), ("PROXY_API_RATE", 1, 10000), ("PROXY_LOGIN_RATE", 1, 10000), ("RABBIT_PORT", 1, 65535), ("REDIS_PORT", 1, 65535)):
        v = g(k)
        if not re.fullmatch(r"\d+", v) or not lo <= int(v) <= hi: err(f"{k} : entier entre {lo} et {hi} attendu (valeur : « {v} »)")
    if not re.fullmatch(r"0?\.\d+|1(\.0+)?", g("RATE_SAFETY_FACTOR")) or float(g("RATE_SAFETY_FACTOR")) <= 0.3: err("RATE_SAFETY_FACTOR : entre 0.3 et 1")
    if g("DB_POOL_MIN").isdigit() and g("DB_POOL_MAX").isdigit() and int(g("DB_POOL_MIN")) > int(g("DB_POOL_MAX")): err("DB_POOL_MIN > DB_POOL_MAX")
    for k in ("MT_CONSUMERS_TRANSACTIONAL", "MT_CONSUMERS_CONFIRMATION", "MT_CONSUMERS_BULK"):
        if not re.fullmatch(r"\d+(-\d+)?", g(k)): err(f"{k} : « min-max » attendu, ex. 4-8")
    for k in ("RENEWAL_CRON", "RETENTION_CRON", "PROJECTION_RECONCILE_CRON"):
        if len(g(k).split()) != 6: err(f"{k} : cron Spring à 6 champs attendu")
    if g("RABBIT_SSL") not in ("true", "false"): err("RABBIT_SSL : true ou false")
    if all(g(k) in ("0", "") for k in ("RETENTION_MESSAGES_DAYS", "RETENTION_CONSENT_DAYS", "RETENTION_AUDIT_DAYS")): warn("conservation : aucune purge configurée (RETENTION_*_DAYS = 0) ; à arrêter avec le conseil juridique avant l'ouverture")

    # opérateurs
    active = []
    for op in OPS:
        p = lambda s: g(f"{op}_{s}")
        if not p("SMSC_HOST"):
            for s in ("SMSC_HOST_2", "SMSC_HOST_3", "SMSC_HOST_4"):
                if p(s): err(f"{op}_{s} renseigné sans {op}_SMSC_HOST")
            continue
        active.append(op)
        for s in ("SYSTEM_ID", "SMSC_PASSWORD", "SHORT_CODES"):
            if not p(s): err(f"{op}_{s} : obligatoire (opérateur {op} activé par {op}_SMSC_HOST)")
        for s, lo, hi in (("SMSC_PORT", 1, 65535), ("TPS", 1, 5000), ("ELINK", 5, 600)):
            if not re.fullmatch(r"\d+", p(s)) or not lo <= int(p(s)) <= hi: err(f"{op}_{s} : entier entre {lo} et {hi} attendu")
        for s in ("SRC_TON", "SRC_NPI", "DST_TON", "DST_NPI"):
            if not re.fullmatch(r"\d+", p(s)) or int(p(s)) > 255: err(f"{op}_{s} : entier 0-255 attendu")
        if p("BIND_MODE") not in ("transceiver", "transmitter", "receiver"): err(f"{op}_BIND_MODE : transceiver | transmitter | receiver")
        if p("DLR_BILLING_RULE") not in ("ON_DELIVERED", "ON_SUBMITTED"): err(f"{op}_DLR_BILLING_RULE : ON_DELIVERED | ON_SUBMITTED")
        if p("LINK_MODE") not in ("failover", "roundrobin"): err(f"{op}_LINK_MODE : failover | roundrobin")
        if p("SHORT_CODES") and not re.fullmatch(r"\d{2,8}(\s*,\s*\d{2,8})*", p("SHORT_CODES")): err(f"{op}_SHORT_CODES : numéros séparés par des virgules")
        if p("MSISDN_PREFIXES") and not re.fullmatch(r"\d{1,8}(\s*,\s*\d{1,8})*", p("MSISDN_PREFIXES")): err(f"{op}_MSISDN_PREFIXES : préfixes numériques séparés par des virgules")
        if not p("MSISDN_PREFIXES") and not g("ROUTING_RANGES_FILE"): warn(f"{op} : ni {op}_MSISDN_PREFIXES ni ROUTING_RANGES_FILE : le routage des MT sans service ne trouvera pas cet opérateur")
        if not re.fullmatch(r"\d+(\.\d+)?", p("RECON_AMOUNT_DIVISOR")) or float(p("RECON_AMOUNT_DIVISOR") or 0) <= 0: err(f"{op}_RECON_AMOUNT_DIVISOR : nombre > 0")
        if p("RECON_STATUS_MAP") and not re.fullmatch(r"[^=;,]+=(CHARGED|ACCEPTED|PENDING|REJECTED|REVERSED|DISPUTED)([;,][^=;,]+=(CHARGED|ACCEPTED|PENDING|REJECTED|REVERSED|DISPUTED))*", p("RECON_STATUS_MAP")):
            err(f"{op}_RECON_STATUS_MAP : « TERME=STATUT;TERME=STATUT » avec STATUT parmi CHARGED, ACCEPTED, PENDING, REJECTED, REVERSED, DISPUTED")
    if not active and not sample: err("aucun opérateur activé : renseigner au moins un bloc *_SMSC_HOST / *_SYSTEM_ID / *_SMSC_PASSWORD / *_SHORT_CODES")
    codes = {}
    for op in active:
        for c in (x.strip() for x in g(f"{op}_SHORT_CODES").split(",") if x.strip()): codes.setdefault(c, []).append(op)
    for c, ops in codes.items():
        if len(ops) > 1: err(f"short code {c} attribué à plusieurs opérateurs : {', '.join(ops)}")

    # fichiers de données (chemins vus du conteneur : /data/x = ./data/x)
    def local(p): return ROOT / "data" / p[len("/data/"):] if p.startswith("/data/") else Path(p)
    for k in ("ROUTING_RANGES_FILE", "ROUTING_PORTED_FILE", "CATALOG_FILE"):
        v = g(k)
        if not v: continue
        f = local(v)
        if not f.is_file(): err(f"{k} : fichier introuvable ({f}) ; le déposer dans ./data et le référencer en /data/...")
            
    known = set(OPS)
    for k, kind in (("ROUTING_RANGES_FILE", "ranges"), ("ROUTING_PORTED_FILE", "ported")):
        v = g(k)
        if v and local(v).is_file():
            bad = 0
            for n, line in enumerate(local(v).read_text(encoding="utf-8").splitlines(), 1):
                l = line.strip()
                if not l or l.startswith("#") or (n == 1 and re.match(r"(?i)(prefix|préfixe|msisdn)", l)): continue
                f = re.split(r"[;,]", l)
                if len(f) < 2 or f[1].strip().upper() not in known or not re.fullmatch(r"\+?\d{1,12}", f[0].strip()):
                    bad += 1
                    if bad <= 3: err(f"{k} ligne {n} invalide : « {l} » (attendu : {'préfixe' if kind == 'ranges' else 'numéro'};TT|ORANGE|OOREDOO)")
            if bad > 3: err(f"{k} : {bad} lignes invalides au total")
    if g("CATALOG_FILE") and local(g("CATALOG_FILE")).is_file():
        try:
            cat = json.loads(local(g("CATALOG_FILE")).read_text(encoding="utf-8"))
            for s in cat.get("services", []):
                if s.get("operator", "").upper() not in known: err(f"CATALOG_FILE : service « {s.get('name')} » : opérateur inconnu « {s.get('operator')} »")
                elif s["operator"].upper() in active and str(s.get("shortCode")) not in [c.strip() for c in g(s["operator"].upper() + "_SHORT_CODES").split(",")]:
                    err(f"CATALOG_FILE : service « {s.get('name')} » : short code {s.get('shortCode')} absent de {s['operator'].upper()}_SHORT_CODES")
        except (ValueError, AttributeError) as e:
            err(f"CATALOG_FILE : JSON invalide ({e})")

    # intégrations externes et exploitation
    host = g("PUBLIC_HOSTNAME")
    if not host or host.endswith("example.tn") or host == "localhost": (err if not sample else warn)("PUBLIC_HOSTNAME : nom DNS public réel attendu")
    for f in ("fullchain.pem", "privkey.pem"):
        if not (ROOT / "certs" / f).is_file(): (err if not sample else warn)(f"certs/{f} : certificat TLS absent")
    for k in ("ALERT_EMAIL_TO", "ALERT_SMTP_HOST", "BACKUP_GPG_RECIPIENT"):
        if not g(k) or "example" in g(k): (err if not sample else warn)(f"{k} : à renseigner (alertes / sauvegardes chiffrées)")
    if g("ALERT_SMTP_HOST") and not re.fullmatch(r"[^:\s]+:\d+", g("ALERT_SMTP_HOST")): err("ALERT_SMTP_HOST : hôte:port attendu")
    if g("ALERT_EMAIL_FROM") and "@" not in g("ALERT_EMAIL_FROM"): err("ALERT_EMAIL_FROM : adresse e-mail attendue")
    if g("ALERT_WEBHOOK_URL") and not g("ALERT_WEBHOOK_URL").startswith("https://"): err("ALERT_WEBHOOK_URL : https:// attendu")
    if not g("OFFSITE_TARGET"): warn("OFFSITE_TARGET vide : les sauvegardes ne sont pas copiées hors site")
    if not g("ADMIN_ALLOWED_CIDRS"): warn("ADMIN_ALLOWED_CIDRS vide : le back-office (/admin/) est joignable depuis tout Internet ; le limiter au VPN d'administration")
    for c in (x.strip() for x in g("ADMIN_ALLOWED_CIDRS").split(",") if x.strip()):
        if not re.fullmatch(r"(\d{1,3}\.){3}\d{1,3}(/\d{1,2})?|[0-9a-fA-F:]+(/\d{1,3})?", c): err(f"ADMIN_ALLOWED_CIDRS : « {c} » n'est pas une adresse ou un réseau valide")
    if g("GRAFANA_BIND") == "0.0.0.0": warn("GRAFANA_BIND=0.0.0.0 : Grafana exposé sur toutes les interfaces")

    for w in warnings: print("AVERTISSEMENT", w)
    for e in errors: print("ERREUR       ", e)
    print(f"\n{path} : {len(errors)} erreur(s), {len(warnings)} avertissement(s)")
    sys.exit(1 if errors or ("--strict" in sys.argv and warnings) else 0)


if __name__ == "__main__":
    main()

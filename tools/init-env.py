#!/usr/bin/env python3
"""Crée (ou complète) le fichier .env UNIQUE à partir de .env.example et GÉNÈRE tous les secrets internes.

  python3 tools/init-env.py            crée .env ; refuse s'il existe déjà (protège vos secrets)
  python3 tools/init-env.py --out FICHIER   écrit ailleurs que ./.env (tests d'intégration)
  python3 tools/init-env.py --fill     complète .env existant : ne remplit que les secrets internes VIDES, ne modifie jamais une valeur renseignée

Secrets générés ([AUTO] dans le modèle) : TOKEN_SECRET, DATA_KEY, CALLBACK_SECRET, mots de passe PostgreSQL / RabbitMQ / Redis / Jasmin / jcli / Grafana /
Prometheus / HA, cookie RabbitMQ, mot de passe du premier administrateur (affiché UNE SEULE FOIS) et les hash associés.
Il ne reste à renseigner à la main que les valeurs externes : opérateurs, domaine, SMTP, sauvegardes, fichiers de données (voir check-env.py).
Hash bcrypt : htpasswd, à défaut l'outil Java du dépôt (target/vas-platform-1.0.0.jar), à défaut Docker (httpd:2.4-alpine)."""
import hashlib, os, re, secrets, shutil, string, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else None
ENV, EXAMPLE = Path(OUT) if OUT else ROOT / ".env", ROOT / ".env.example"
ALNUM = string.ascii_letters + string.digits
BAD = ("password", "secret", "change", "dev", "example", "test")   # sous-chaînes refusées par ProductionGuard


def rnd(n, alphabet=ALNUM):
    while True:
        v = "".join(secrets.choice(alphabet) for _ in range(n))
        if not any(b in v.lower() for b in BAD):
            return v


def bcrypt(pw):
    if shutil.which("htpasswd"):
        out = subprocess.run(["htpasswd", "-nbBC", "10", "", pw], capture_output=True, text=True)
        if out.returncode == 0: return "{bcrypt}" + out.stdout.strip().lstrip(":").replace("$2y$", "$2a$")
    jar = ROOT / "target" / "vas-platform-1.0.0.jar"
    if shutil.which("java") and jar.exists():
        out = subprocess.run(["java", "-Dloader.main=tn.vas.tools.HashPassword", "-cp", str(jar), "org.springframework.boot.loader.launch.PropertiesLauncher", pw], capture_output=True, text=True)
        if out.returncode == 0 and out.stdout.strip().startswith("{bcrypt}"): return out.stdout.strip()
    if shutil.which("docker"):
        out = subprocess.run(["docker", "run", "--rm", "httpd:2.4-alpine", "htpasswd", "-nbBC", "10", "", pw], capture_output=True, text=True)
        if out.returncode == 0: return "{bcrypt}" + out.stdout.strip().lstrip(":").replace("$2y$", "$2a$")
    return None


def main():
    fill = "--fill" in sys.argv
    if ENV.exists() and not fill:
        sys.exit(f"{ENV} existe déjà : rien n'est modifié. Utiliser --fill pour compléter les secrets vides sans toucher au reste.")
    src = (ENV if fill and ENV.exists() else EXAMPLE).read_text(encoding="utf-8")
    values = {}
    def need(k): return not re.search(rf"^{k}=\S", src, re.M)   # clé absente ou vide
    gen = {}
    if need("TOKEN_SECRET"): gen["TOKEN_SECRET"] = rnd(48)
    if need("DATA_KEY"): gen["DATA_KEY"] = rnd(64)
    if need("CALLBACK_SECRET"): gen["CALLBACK_SECRET"] = rnd(32)
    for k, n in (("DB_PASSWORD", 28), ("RABBIT_PASSWORD", 28), ("REDIS_PASSWORD", 28), ("GRAFANA_PASSWORD", 24), ("DB_SUPERUSER_PASSWORD", 28), ("DB_REPLICATION_PASSWORD", 28), ("RABBIT_COOKIE", 32)):
        if need(k): gen[k] = rnd(n)
    if need("JASMIN_PASSWORD"): gen["JASMIN_PASSWORD"] = rnd(14)         # 12 à 16 caractères alphanumériques (limite de Jasmin)
    if need("JCLI_PASSWORD") or need("JCLI_PASSWORD_MD5"):
        pw = re.search(r"^JCLI_PASSWORD=(\S+)", src, re.M)
        gen["JCLI_PASSWORD"] = pw.group(1) if pw else rnd(24)
        gen["JCLI_PASSWORD_MD5"] = hashlib.md5(gen["JCLI_PASSWORD"].encode()).hexdigest()
    shown = []
    if need("ADMIN_PASSWORD_HASH"):
        pw = rnd(20) + "7"
        h = bcrypt(pw)
        if h:
            gen["ADMIN_PASSWORD_HASH"] = "'" + h + "'"
            shown.append(("Premier administrateur", re.search(r"^ADMIN_USER=(\S+)", src, re.M).group(1) if re.search(r"^ADMIN_USER=(\S+)", src, re.M) else "admin", pw))
        else:
            print("! hash bcrypt impossible (ni htpasswd, ni Java + jar construit, ni Docker) : renseigner ADMIN_PASSWORD_HASH à la main (docs/19-configuration-env.md)")
    if need("PROMETHEUS_PASSWORD") or need("PROMETHEUS_PASSWORD_HASH"):
        pw = re.search(r"^PROMETHEUS_PASSWORD=(\S+)", src, re.M)
        pw = pw.group(1) if pw else rnd(24)
        gen["PROMETHEUS_PASSWORD"] = pw
        if need("PROMETHEUS_PASSWORD_HASH"):
            h = bcrypt(pw)
            if h: gen["PROMETHEUS_PASSWORD_HASH"] = "'" + h + "'"
    out = []
    for line in src.split("\n"):
        m = re.match(r"^([A-Z][A-Z0-9_]*)=(.*)$", line)
        if m and m.group(1) in gen:
            comment = re.search(r"\s+#.*$", m.group(2))
            line = f"{m.group(1)}={gen[m.group(1)]}" + (comment.group(0) if comment else "")
        out.append(line)
    ENV.write_text("\n".join(out), encoding="utf-8")
    os.chmod(ENV, 0o600)
    print(f"{ENV} {'complété' if fill else 'créé'} (droits 600) : {len(gen)} secret(s) généré(s).")
    for what, user, pw in shown:
        print(f"\n  {what} : utilisateur « {user} », mot de passe « {pw} »\n  -> NOTEZ-LE MAINTENANT dans votre coffre de secrets : il n'est stocké nulle part en clair (changement obligatoire à la première connexion).")
    print("\nSAUVEGARDEZ HORS SERVEUR : DATA_KEY (sa perte rend les numéros chiffrés irrécupérables) et une copie chiffrée de ce fichier.")
    print("Prochaine étape : renseigner les valeurs [À RENSEIGNER] (opérateurs, domaine, SMTP...), puis  python3 tools/check-env.py")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Régénère docs/08-dictionnaire-donnees.md depuis le schéma PostgreSQL réel (après migrations Flyway).
Usage : PGHOST=/tmp PGPORT=5433 PGUSER=postgres python3 tools/gen_dictionary.py [base=vas]"""
import re, subprocess, sys, pathlib

DB = sys.argv[1] if len(sys.argv) > 1 else "vas"
OUT = pathlib.Path(__file__).resolve().parent.parent / "docs" / "08-dictionnaire-donnees.md"
# Descriptions : reprises du document existant, complétées pour les tables ajoutées depuis.
NEW = {
    "billing_period": "Périodes de facturation clôturées (totaux figés ; plus aucune transition de ledger dans ces dates).",
    "partner_payout": "Reversements aux partenaires (PENDING → PAID, créateur ≠ payeur, aucun chevauchement de période).",
    "quiz_question": "Questions de quiz par service (rang, réponses acceptées séparées par « | », points, réponses).",
    "quiz_progress": "Progression d'un participant (question courante, score, terminé) : une partie par numéro et par service.",
    "content_item": "Contenus premium diffusables par lien (code, titre, texte/URL, nombre d'usages et durée du lien).",
    "content_token": "Jetons de lien à usage limité émis par numéro (hash, expiration, compteur d'usages).",
    "vote_option": "Options de vote déclarées d'un service (code court + libellé) ; sans option, tout texte est accepté.",
    "vote_ballot": "Bulletins de vote : un par numéro et par service (unicité), option choisie et horodatage.",
}
old = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
desc = {m.group(1): m.group(2).strip() for m in re.finditer(r"## `(\w+)`\n\n(.+?)\n\n\| Colonne", old, re.S)}
desc.update(NEW)

def q(sql):
    r = subprocess.run(["psql", "-d", DB, "-At", "-F", "|", "-c", sql], capture_output=True, text=True, check=True)
    return [l.split("|") for l in r.stdout.splitlines() if l]

tables = [r[0] for r in q("select table_name from information_schema.tables where table_schema='public' and table_name <> 'flyway_schema_history' order by 1")]
fk = {(r[0], r[1]): r[2] for r in q("""select kcu.table_name, kcu.column_name, ccu.table_name from information_schema.key_column_usage kcu
  join information_schema.table_constraints tc using (constraint_name, table_schema)
  join information_schema.constraint_column_usage ccu using (constraint_name, table_schema) where tc.constraint_type='FOREIGN KEY' and kcu.table_schema='public'""")}
mig = q("select max(version::int) from flyway_schema_history where success")[0][0]
out = ["# Dictionnaire de données", "",
       f"Généré depuis le schéma PostgreSQL réel après application des migrations Flyway V1–V{mig} (`src/main/resources/db/migration` et `db/vendor/postgresql`) par `tools/gen_dictionary.py`.",
       "Montants : `DECIMAL(12,3)` (millimes de dinar) ; totaux de période et reversements : `DECIMAL(14,3)`. Horodatages : `TIMESTAMP WITH TIME ZONE` (UTC).",
       "Les numéros de téléphone (`msisdn`) sont stockés **chiffrés** (`enc:v1:…`, AES-256 déterministe, clé `vas.data-key`) : colonnes `VARCHAR(100)`.", ""]
for t in tables:
    out += [f"\n## `{t}`", "", desc.get(t, "—"), "", "| Colonne | Type | Null | Défaut / référence |", "|---|---|---|---|"]
    for c, typ, ln, mx, nul, dflt in q(f"select column_name, data_type, coalesce(character_maximum_length::text,''), '', is_nullable, coalesce(column_default,'') from information_schema.columns where table_schema='public' and table_name='{t}' order by ordinal_position"):
        typ = {"character varying": f"character varying({ln})" if ln else "character varying", "timestamp with time zone": "timestamp with time zone"}.get(typ, typ)
        ref = f"→ `{fk[(t, c)]}`" if (t, c) in fk else ("" if dflt.startswith("nextval") or "identity" in dflt else dflt)
        out.append(f"| `{c}` | {typ} | {'oui' if nul == 'YES' else 'non'} | {ref} |")
OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
print(f"{len(tables)} tables -> {OUT}")

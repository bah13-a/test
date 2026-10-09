#!/usr/bin/env python3
"""Génère le PDF de chiffrage détaillé (réponse au bordereau de prix §19 du CDC VAS/SMPP Tunisie V1.0).
Usage : python3 generate_chiffrage.py [sortie.pdf]   (dépendance : reportlab)
Toutes les hypothèses (taux, JH, hébergement) sont des paramètres de ce fichier : modifier puis régénérer.
"""
import sys
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle, KeepTogether)

pdfmetrics.registerFont(TTFont("DV", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
pdfmetrics.registerFont(TTFont("DVB", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"))

OUT = sys.argv[1] if len(sys.argv) > 1 else "Chiffrage_detaille_VAS_SMPP_Tunisie_V1.pdf"
TVA = 0.19
CONTINGENCY = 0.10
MAINT_RATE = 0.18          # maintenance annuelle = 18 % du coût de réalisation V1
SUPPORT_247 = 36000        # forfait annuel support 24/7 (astreinte 2 ingénieurs, rotation)

# ---- Taux journaliers (TND HT / JH) -----------------------------------------------------------
RATES = {"ARCHI": 900, "SDEV": 700, "DEV": 550, "DEVOPS": 700, "QA": 500, "PM": 800, "UX": 550}
LABEL = {"ARCHI": "Architecte / Tech lead", "SDEV": "Développeur senior", "DEV": "Développeur",
         "DEVOPS": "Ingénieur DevOps/SRE", "QA": "Ingénieur QA / test", "PM": "Chef de projet", "UX": "UX/UI designer"}
ORDER = ["ARCHI", "SDEV", "DEV", "DEVOPS", "QA", "PM", "UX"]

# ---- Postes du bordereau §19 : JH par profil, délai (semaines) et commentaire ----------------
LINES = [
 ("Cadrage & architecture",              dict(ARCHI=10, PM=8, SDEV=4, DEVOPS=3),             "3 sem.",  "Ateliers, HLD/LLD, backlog, collecte paramètres opérateurs (Annexe B)."),
 ("Installation/configuration gateway SMPP", dict(DEVOPS=9, SDEV=6, ARCHI=2),                 "3 sem.",  "Jasmin (open source), routes MT/MO, utilisateurs par opérateur, simulateur SMPP."),
 ("Connecteur Tunisie Telecom",          dict(SDEV=6, DEVOPS=3, QA=3),                         "3 sem.",  "VPN/IPsec, bind, TON/NPI, DLR, recette opérateur. Dépend des délais TT."),
 ("Connecteur Orange Tunisie",           dict(SDEV=5, DEVOPS=2, QA=2),                         "3 sem.",  "Réutilise le socle TT ; paramètres propres à Orange."),
 ("Connecteur Ooredoo Tunisie",          dict(SDEV=5, DEVOPS=2, QA=2),                         "3 sem.",  "Réutilise le socle TT ; paramètres propres à Ooredoo."),
 ("Moteur VAS",                          dict(ARCHI=6, SDEV=22, DEV=24, QA=8),                 "10 sem.", "Services, short codes, keywords, scénarios MO→MT, consentement, STOP/AIDE, FR/AR/EN."),
 ("Billing métier / ledger",             dict(ARCHI=4, SDEV=14, DEV=12, QA=5),                 "7 sem.",  "Ledger auditable, catalogue tarifaire versionné, partage de revenus, idempotence."),
 ("Réconciliation opérateurs",           dict(SDEV=8, DEV=8, QA=4),                            "5 sem.",  "Import CSV/XLSX, mapping configurable, écarts, dashboard et exports."),
 ("API REST / Webhooks",                 dict(SDEV=8, DEV=8, QA=4, ARCHI=2),                   "5 sem.",  "OpenAPI, clés/scopes/quotas, webhooks HMAC avec retry + DLQ."),
 ("Back-office",                         dict(SDEV=10, DEV=18, UX=6, QA=8, ARCHI=2),           "8 sem.",  "React, RBAC 7 rôles, recherche messages, console réconciliation, exports CSV/XLSX/PDF."),
 ("Portail partenaires",                 dict(SDEV=5, DEV=10, UX=4, QA=4),                     "5 sem.",  "Dashboard partenaire isolé (volumes, taux de livraison, montants estimés vs rapprochés)."),
 ("Sécurité & RBAC/MFA",                 dict(ARCHI=3, SDEV=8, DEVOPS=4, QA=3),                "4 sem.",  "MFA TOTP, audit non modifiable, OWASP, secrets, scan dépendances."),
 ("Monitoring & alerting",               dict(DEVOPS=8, SDEV=3, QA=1),                         "3 sem.",  "Prometheus, Grafana, Loki/OpenSearch, alertes email/Teams/Slack/SMS."),
 ("HA / PRA / sauvegardes",              dict(DEVOPS=12, ARCHI=3, QA=3),                       "4 sem.",  "PostgreSQL HA (Patroni), RabbitMQ/Redis persistants, sauvegardes chiffrées, test de restauration."),
 ("Tests de charge & sécurité",          dict(QA=10, DEVOPS=4, SDEV=4, ARCHI=2),               "4 sem.",  "50 SMS/s/opérateur → 200 agrégés, tests de panne, test d'intrusion applicatif."),
 ("Documentation & formation",           dict(PM=4, SDEV=4, DEVOPS=3, QA=3, UX=2, DEV=2),      "3 sem.",  "HLD/LLD, guides admin/NOC/utilisateur, PRA, formation et PV de transfert."),
 ("Mise en production & hypercare",      dict(DEVOPS=6, SDEV=5, PM=4, QA=3),                   "4 sem.",  "Go-live, 4 semaines d'hypercare, transfert code/secrets/comptes."),
]
# gestion de projet transversale (pilotage COPIL, suivi, risques) : 10 % du coût des postes ci-dessus
PM_TRANSVERSE = 0.08

OPTIONS = [  # (option, JH, détail, délai, conditions)
 ("USSD (gateway + applications)",     55, "Connecteur USSD, menus, sessions", "10 sem.", "Accès USSD GW opérateur requis"),
 ("Direct Carrier Billing (DCB)",      70, "Intégration facturation opérateur + ledger", "12 sem.", "Contrat DCB opérateur requis"),
 ("IVR / audiotel",                    45, "Serveur IVR, flux audio, CDR", "8 sem.", "Numéro audiotel & trunk SIP"),
 ("RCS / WhatsApp Business",           50, "Connecteurs canaux, templates, opt-in", "8 sem.", "Frais BSP/Meta hors prix"),
 ("BI avancée / Data warehouse",       60, "Modèle analytique, ETL, tableaux de bord", "10 sem.", "Hébergement DWH en sus"),
 ("Antifraude avancée",                48, "Règles, scoring statistique, blocage", "8 sem.", "Historique ≥ 3 mois conseillé"),
 ("Application mobile partenaires",    65, "App iOS/Android (cross-platform)", "10 sem.", "Comptes stores au nom du client"),
 ("SMPP Server pour clients externes", 22, "Exposition de comptes SMPP, quotas, facturation", "4 sem.", "Jasmin SMPP server + durcissement"),
]
OPT_RATE = 680  # taux moyen pondéré options

HOSTING = [  # (poste, quantité, prix unitaire mensuel TND, commentaire)
 ("PROD - VM applicative (4 vCPU / 8 Go)",        2, 220, "Active/active derrière load balancer"),
 ("PROD - VM base PostgreSQL (4 vCPU / 16 Go)",   2, 300, "Primaire + réplica synchrone (Patroni)"),
 ("PROD - VM broker + Redis + Jasmin (4 vCPU / 8 Go)", 2, 220, "RabbitMQ/Redis/Jasmin en paire"),
 ("PROD - VM monitoring/logs/bastion (4 vCPU / 8 Go)", 1, 220, "Prometheus, Grafana, Loki, bastion SSH"),
 ("PREPROD/UAT - VM (4 vCPU / 8 Go)",             2, 180, "Environnement réduit, tests opérateurs"),
 ("DEV/SIT - VM (2 vCPU / 4 Go)",                 1, 100, "Simulateur SMPP, CI"),
 ("Stockage bloc + sauvegardes chiffrées hors site", 1, 250, "~2 To, rétention 30 j"),
 ("Bande passante / IP publiques / pare-feu géré", 1, 180, "VPN IPsec opérateurs : frais opérateurs exclus"),
]

def tnd(x):
    return f"{x:,.0f}".replace(",", " ")

def line_cost(d):
    return sum(RATES[p] * jh for p, jh in d.items())

# ---- Calculs -----------------------------------------------------------------------------------
rows = []
for name, d, delay, com in LINES:
    jh = sum(d.values())
    rows.append((name, jh, line_cost(d), delay, com, d))
sub_jh = sum(r[1] for r in rows)
sub_cost = sum(r[2] for r in rows)
pm_jh = round(sub_jh * PM_TRANSVERSE)
pm_cost = pm_jh * RATES["PM"]
build_jh = sub_jh + pm_jh
build_ht = sub_cost + pm_cost
conting = round(build_ht * CONTINGENCY)
total_v1_ht = build_ht + conting
maint = round(build_ht * MAINT_RATE)
host_month = sum(q * p for _, q, p, _ in HOSTING)
host_year = host_month * 12
lic = 0
tco3 = total_v1_ht + 3 * (maint + host_year + lic)

# ---- Styles ------------------------------------------------------------------------------------
ss = getSampleStyleSheet()
B = ParagraphStyle("B", parent=ss["Normal"], fontName="DV", fontSize=8.6, leading=11.5)
S = ParagraphStyle("S", parent=B, fontSize=7.4, leading=9.5)
SB = ParagraphStyle("SB", parent=S, fontName="DVB")
H1 = ParagraphStyle("H1", parent=B, fontName="DVB", fontSize=14, leading=18, spaceBefore=6, spaceAfter=6, textColor=colors.HexColor("#0b3d91"))
H2 = ParagraphStyle("H2", parent=B, fontName="DVB", fontSize=10.5, leading=14, spaceBefore=8, spaceAfter=4, textColor=colors.HexColor("#0b3d91"))
T = ParagraphStyle("T", parent=B, fontName="DVB", fontSize=22, leading=28, textColor=colors.HexColor("#0b3d91"))
NOTE = ParagraphStyle("N", parent=B, fontSize=7.8, leading=10.5, textColor=colors.HexColor("#444444"))

def P(t, s=S): return Paragraph(str(t), s)

def table(data, widths, header=True, align_right=(), total_last=False, zebra=True):
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    st = [("FONTNAME", (0, 0), (-1, -1), "DV"), ("FONTSIZE", (0, 0), (-1, -1), 7.4),
          ("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#c9ced6")), ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
          ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3)]
    if header:
        st += [("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0b3d91")), ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
               ("FONTNAME", (0, 0), (-1, 0), "DVB")]
    if zebra:
        for i in range(1, len(data)):
            if i % 2 == 0: st.append(("BACKGROUND", (0, i), (-1, i), colors.HexColor("#f3f5f9")))
    for c in align_right: st.append(("ALIGN", (c, 0), (c, -1), "RIGHT"))
    if total_last:
        st += [("BACKGROUND", (0, -1), (-1, -1), colors.HexColor("#dfe7f7")), ("FONTNAME", (0, -1), (-1, -1), "DVB")]
    t.setStyle(TableStyle(st))
    return t

def footer(c, d):
    c.saveState(); c.setFont("DV", 7); c.setFillColor(colors.grey)
    c.drawString(15 * mm, 9 * mm, "Plateforme VAS / SMS Premium SMPP v3.4 - Chiffrage détaillé V1 - Confidentiel")
    c.drawRightString(195 * mm, 9 * mm, f"Page {d.page}"); c.restoreState()

doc = SimpleDocTemplate(OUT, pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm, topMargin=14 * mm, bottomMargin=16 * mm,
                        title="Chiffrage détaillé - Plateforme VAS SMPP Tunisie V1", author="Réponse à consultation")
W = 180 * mm
el = []

# ---- Page de garde -----------------------------------------------------------------------------
el += [Spacer(1, 30 * mm), P("CHIFFRAGE DÉTAILLÉ", T), P("Plateforme VAS / SMS Premium - SMPP v3.4", ParagraphStyle("x", parent=H1, fontSize=16)),
       P("Interconnexion MO / MT / DLR - Tunisie Telecom, Orange Tunisie, Ooredoo Tunisie", B), Spacer(1, 6 * mm),
       P("Réponse financière à la consultation « Cahier des charges technique &amp; fonctionnel V1.0 » du 05/10/2026 (bordereau §19)", B),
       Spacer(1, 10 * mm)]
kpi = [["Coût de réalisation V1 (HT)", f"{tnd(build_ht)} TND"],
       ["Aléas / risques (10 %)", f"{tnd(conting)} TND"],
       ["TOTAL V1 HT", f"{tnd(total_v1_ht)} TND"],
       ["TOTAL V1 TTC (TVA 19 %)", f"{tnd(total_v1_ht * (1 + TVA))} TND"],
       ["Charge totale", f"{build_jh} JH"],
       ["Récurrent annuel HT (maintenance + hébergement)", f"{tnd(maint + host_year)} TND / an"],
       ["TCO 3 ans HT (V1 + 3 ans de récurrent)", f"{tnd(tco3)} TND"]]
el.append(table(kpi, [110 * mm, 70 * mm], header=False, align_right=(1,)))
el += [Spacer(1, 8 * mm),
       P("Montants en dinars tunisiens (TND), hors taxes sauf mention contraire. Estimation budgétaire fondée sur les taux et hypothèses "
         "de la section 1 : elle doit être confirmée après la phase de cadrage et ne constitue pas une offre ferme tant que les "
         "paramètres opérateurs (Annexe B du cahier des charges) ne sont pas fournis.", NOTE), PageBreak()]

# ---- 1. Hypothèses et taux ---------------------------------------------------------------------
el.append(P("1. Hypothèses de chiffrage et taux journaliers", H1))
el.append(P("Périmètre : lots A à H du CDC §2.1 (MUST + portail partenaire SHOULD). Dimensionnement §12.1 : 50 SMS/s soutenus par opérateur, "
            "extensible à 200 SMS/s agrégés. Stack : Linux, Jasmin SMS Gateway (open source), backend Java 21 / Spring Boot, PostgreSQL, Redis, "
            "RabbitMQ, API REST (OpenAPI), back-office React, Prometheus/Grafana, Loki ou OpenSearch, CI/CD GitLab CI ou GitHub Actions.", B))
el.append(Spacer(1, 3 * mm))
rt = [["Profil", "Taux (TND HT / JH)"]] + [[LABEL[p], tnd(RATES[p])] for p in ORDER]
el.append(table(rt, [110 * mm, 70 * mm], align_right=(1,)))
el.append(Spacer(1, 3 * mm))
for t in [
    "Taux journaliers : hypothèses de marché pour une société de services tunisienne (profils seniors/confirmés) - à ajuster selon l'intégrateur retenu.",
    f"Gestion de projet transversale (COPIL, suivi, risques) : {int(PM_TRANSVERSE * 100)} % de la charge des postes, au taux Chef de projet.",
    f"Aléas et risques : {int(CONTINGENCY * 100)} % du coût de réalisation (dépendances opérateurs, évolutions de format DLR, délais de recette).",
    "Licences : 100 % de composants open source (Jasmin, PostgreSQL, Redis, RabbitMQ, Prometheus, Grafana, Loki). Aucun coût de licence ; "
    "un contrat de support éditeur optionnel est possible mais non inclus.",
    f"Maintenance annuelle : {int(MAINT_RATE * 100)} % du coût de réalisation V1 (corrective, préventive/sécurité, rapport mensuel). "
    "Évolutions facturées à part (forfait de jours).",
    "Exclus : frais et abonnements opérateurs (activation short code, VPN, frais INT/ATT), conseil juridique et déclarations réglementaires, "
    "SMS de test, certificats TLS commerciaux, frais de transaction et coûts de contenus.",
    "Le client fournit : paramètres SMPP et jeux de tests opérateurs, accès VPN, interlocuteurs NOC, validation métier dans les 5 jours ouvrés.",
]:
    el.append(P("• " + t, B)); el.append(Spacer(1, 1.2 * mm))

# ---- 2. Bordereau de prix (format §19) ---------------------------------------------------------
el.append(PageBreak())
el.append(P("2. Bordereau de prix (format du CDC §19)", H1))
data = [["Poste", "JH", "Prix HT (TND)", "Délai", "Commentaires"]]
for name, jh, cost, delay, com, _ in rows:
    data.append([P(name), jh, tnd(cost), delay, P(com)])
data.append([P("Gestion de projet transversale"), pm_jh, tnd(pm_cost), "continu", P("Pilotage, COPIL, suivi des risques et des dépendances opérateurs.")])
data.append([P("Aléas et risques (10 %)"), "-", tnd(conting), "-", P("Provision pour dépendances externes et recette opérateurs.")])
data.append([P("<b>TOTAL V1 (réalisation) HT</b>"), build_jh, tnd(total_v1_ht), "≈ 28 sem.", P("Hors récurrent. TTC : " + tnd(total_v1_ht * (1 + TVA)) + " TND.")])
el.append(table(data, [48 * mm, 11 * mm, 24 * mm, 17 * mm, 80 * mm], align_right=(1, 2), total_last=True))
el.append(Spacer(1, 4 * mm))
el.append(P("Postes récurrents (hors TOTAL V1)", H2))
rec = [["Poste", "Forfait / JH", "Prix HT (TND)", "Périodicité", "Commentaires"],
       [P("Maintenance annuelle"), P(f"{int(MAINT_RATE*100)} % du build"), tnd(maint), "par an", P("Corrective, préventive, sécurité, support heures ouvrées, rapport mensuel (SLA §16).")],
       [P("Support 24/7 optionnel"), "forfait", tnd(SUPPORT_247), "par an", P("Astreinte P1/P2 24/7, prise en charge ≤ 30 min (§16.1).")],
       [P("Licences tierces annuelles"), "-", tnd(lic), "par an", P("Open source : 0 TND. Hors support éditeur optionnel.")],
       [P("Hébergement mensuel estimatif"), "voir §5", tnd(host_month), "par mois", P(f"Soit {tnd(host_year)} TND / an. Prod HA + préprod + dev (détail §5).")]]
el.append(table(rec, [46 * mm, 22 * mm, 24 * mm, 20 * mm, 68 * mm], align_right=(2,)))

# ---- 3. Détail par profil ----------------------------------------------------------------------
el.append(PageBreak())
el.append(P("3. Détail de la charge par poste et par profil (JH)", H1))
hdr = ["Poste"] + ["Archi", "Dev sr", "Dev", "DevOp", "QA", "PM", "UX"] + ["Total JH", "Coût HT"]
d3 = [hdr]
tot = {p: 0 for p in ORDER}
for name, jh, cost, _, _, d in rows:
    d3.append([P(name)] + [d.get(p, "") or "-" for p in ORDER] + [jh, tnd(cost)])
    for p in ORDER: tot[p] += d.get(p, 0)
tot["PM"] += pm_jh
d3.append([P("Gestion de projet transversale")] + ["-"] * 5 + [pm_jh, "-"] + [pm_jh, tnd(pm_cost)])
d3.append([P("<b>TOTAL</b>")] + [tot[p] for p in ORDER] + [build_jh, tnd(build_ht)])
w3 = [50 * mm] + [12 * mm] * 7 + [15 * mm, 21 * mm]
el.append(table(d3, w3, align_right=tuple(range(1, 10)), total_last=True))
el.append(Spacer(1, 3 * mm))
mix = [["Profil", "JH", "Part", "Coût HT (TND)"]]
for p in ORDER:
    mix.append([LABEL[p], tot[p], f"{100 * tot[p] / build_jh:.0f} %", tnd(tot[p] * RATES[p])])
mix.append(["Total", build_jh, "100 %", tnd(sum(tot[p] * RATES[p] for p in ORDER))])
el.append(P("Répartition par profil", H2))
el.append(table(mix, [80 * mm, 25 * mm, 25 * mm, 50 * mm], align_right=(1, 2, 3), total_last=True))
el.append(Spacer(1, 2 * mm))
el.append(P("Le coût par profil est calculé avant aléas ; la ligne Chef de projet inclut la gestion transversale.", NOTE))

# ---- 4. Planning et jalons ---------------------------------------------------------------------
el.append(PageBreak())
el.append(P("4. Planning de référence et jalons de facturation", H1))
ph = [["Phase (CDC §17)", "Semaines", "Postes concernés", "Livrable de sortie"],
      ["0. Cadrage", "S1 - S3", "Cadrage & architecture", "HLD + planning"],
      ["1. Socle SMPP", "S3 - S8", "Gateway, connecteur test, simulateur", "Démo MO/MT/DLR"],
      ["2. Moteur VAS", "S6 - S16", "Moteur VAS, billing, API", "MVP fonctionnel"],
      ["3. Back-office", "S12 - S20", "Back-office, portail, sécurité", "Version UAT"],
      ["4. Intégration opérateurs", "S14 - S22", "3 connecteurs, réconciliation, monitoring", "PV tests opérateurs"],
      ["5. Recette & charge", "S21 - S25", "HA/PRA, tests charge & sécurité, doc", "PV de recette"],
      ["6. Production", "S25 - S28 (+4 hypercare)", "Mise en production, formation, transfert", "Mise en service"]]
el.append(table([[P(c, SB if i == 0 else S) for c in r] for i, r in enumerate(ph)], [42 * mm, 36 * mm, 60 * mm, 42 * mm]))
el.append(Spacer(1, 3 * mm))
el.append(P("Durée de référence : ≈ 28 semaines hors hypercare, sous réserve de la disponibilité des opérateurs (VPN, comptes SMPP, jeux de tests) - "
            "principal risque de calendrier, hors maîtrise du prestataire.", B))
el.append(P("Échéancier de paiement proposé (sur total V1 HT)", H2))
mil = [("Commande / démarrage du cadrage", 0.20), ("HLD validé + démo socle SMPP (fin phase 1)", 0.15), ("MVP fonctionnel (fin phase 2)", 0.20),
       ("Version UAT livrée (fin phase 3)", 0.15), ("PV de recette signé (fin phase 5)", 0.20), ("Mise en production + transfert (solde)", 0.10)]
md = [["Jalon", "%", "Montant HT (TND)"]] + [[a, f"{int(p*100)} %", tnd(total_v1_ht * p)] for a, p in mil] + [["Total", "100 %", tnd(total_v1_ht)]]
el.append(table(md, [110 * mm, 20 * mm, 50 * mm], align_right=(1, 2), total_last=True))

# ---- 5. Hébergement ----------------------------------------------------------------------------
el.append(P("5. Hébergement mensuel estimatif", H1))
hd = [["Poste", "Qté", "PU mensuel (TND)", "Total mensuel", "Commentaire"]]
for n, q, pu, c in HOSTING: hd.append([P(n), q, tnd(pu), tnd(q * pu), P(c)])
hd.append(["Total mensuel HT", "", "", tnd(host_month), f"{tnd(host_year)} TND / an"])
el.append(table(hd, [62 * mm, 10 * mm, 25 * mm, 25 * mm, 58 * mm], align_right=(1, 2, 3), total_last=True))
el.append(Spacer(1, 2 * mm))
el.append(P("Prix indicatifs d'un hébergement cloud / datacenter local ; à remplacer par les devis de l'hébergeur retenu. Le client reste propriétaire des comptes et des secrets (CDC §15.2).", NOTE))

# ---- 6. Options --------------------------------------------------------------------------------
el.append(PageBreak())
el.append(P("6. Options à chiffrer séparément (CDC §19.1)", H1))
od = [["Option", "JH", "Prix HT (TND)", "Délai", "Périmètre", "Conditions"]]
opt_total = 0
for n, jh, det, delay, cond in OPTIONS:
    od.append([P(n), jh, tnd(jh * OPT_RATE), delay, P(det), P(cond)]); opt_total += jh * OPT_RATE
od.append(["Total si toutes retenues", sum(o[1] for o in OPTIONS), tnd(opt_total), "", "", ""])
el.append(table(od, [40 * mm, 10 * mm, 24 * mm, 16 * mm, 48 * mm, 42 * mm], align_right=(1, 2), total_last=True))
el.append(Spacer(1, 2 * mm))
el.append(P(f"Taux moyen pondéré des options : {tnd(OPT_RATE)} TND / JH (mix architecte, développeurs, QA). Chaque option est activable ultérieurement sans refonte (architecture modulaire, gateway remplaçable).", NOTE))

# ---- 7. TCO ------------------------------------------------------------------------------------
el.append(P("7. Coût total de possession (TCO) sur 3 ans", H1))
tc = [["Poste", "An 0 (V1)", "An 1", "An 2", "An 3", "Total"]]
tc.append(["Réalisation V1 (aléas inclus)", tnd(total_v1_ht), "-", "-", "-", tnd(total_v1_ht)])
tc.append(["Maintenance", "-", tnd(maint), tnd(maint), tnd(maint), tnd(3 * maint)])
tc.append(["Hébergement", "-", tnd(host_year), tnd(host_year), tnd(host_year), tnd(3 * host_year)])
tc.append(["Licences tierces", "-", "0", "0", "0", "0"])
tc.append(["Total HT", tnd(total_v1_ht), tnd(maint + host_year), tnd(maint + host_year), tnd(maint + host_year), tnd(tco3)])
el.append(table(tc, [52 * mm, 28 * mm, 25 * mm, 25 * mm, 25 * mm, 25 * mm], align_right=(1, 2, 3, 4, 5), total_last=True))
el.append(Spacer(1, 2 * mm))
el.append(P(f"Hors support 24/7 optionnel ({tnd(SUPPORT_247)} TND / an), hors options, hors frais opérateurs. TVA 19 % en sus.", NOTE))

# ---- 8. Avancement du dépôt et reste à faire ------------------------------------------------
PROGRESS = {  # estimation d'avancement du code livré dans le dépôt (0-1) et reste à faire, par poste du bordereau
 "Cadrage & architecture": (0.80, "Ateliers avec les opérateurs, collecte des paramètres (Annexe B), validation du HLD"),
 "Installation/configuration gateway SMPP": (0.55, "Validation de provision.sh sur un Jasmin réel, durcissement, exploitation"),
 "Connecteur Tunisie Telecom": (0.35, "VPN, bind réel, formats DLR, recette opérateur"),
 "Connecteur Orange Tunisie": (0.35, "VPN, bind réel, formats DLR, recette opérateur"),
 "Connecteur Ooredoo Tunisie": (0.35, "VPN, bind réel, formats DLR, recette opérateur"),
 "Moteur VAS": (0.85, "Scénarios spécifiques (quiz), ajustements issus de la recette métier"),
 "Billing métier / ledger": (0.85, "Règles propres aux opérateurs (DCB/premium), corrections comptables"),
 "Réconciliation opérateurs": (0.85, "Formats réels des relevés, dashboard de suivi"),
 "API REST / Webhooks": (0.90, "OAuth2 optionnel, portail développeur"),
 "Back-office": (0.85, "Retours UAT, ergonomie, éditions avancées"),
 "Portail partenaires": (0.85, "Retours partenaires, indicateurs supplémentaires"),
 "Sécurité & RBAC/MFA": (0.75, "Test d'intrusion, rotation de secrets, durcissement OS"),
 "Monitoring & alerting": (0.75, "Alertmanager (e-mail/Teams/SMS), seuils calés sur le trafic réel"),
 "HA / PRA / sauvegardes": (0.50, "Validation de la topologie HA et d'une bascule en préproduction, PRA joué"),
 "Tests de charge & sécurité": (0.40, "Charge sur infra cible (200 SMS/s), tests de panne, pen-test"),
 "Documentation & formation": (0.70, "Formation et PV de transfert, mise à jour post-recette"),
 "Mise en production & hypercare": (0.10, "Go-live, hypercare, transfert des accès"),
}
el.append(PageBreak())
el.append(P("8. Avancement du code livré dans le dépôt et reste à faire", H1))
el.append(P("Le dépôt contient une plateforme fonctionnelle (32 tests automatisés, parcours navigateur de bout en bout, validation sur PostgreSQL et Redis réels, "
            "sauvegarde/restauration GPG exécutée, test de charge mesuré). Les pourcentages ci-dessous sont des estimations d'avancement par poste, à confirmer en revue de code ; "
            "ils ne modifient pas les montants des sections 2 à 7, qui chiffrent la V1 complète telle que décrite au CDC.", B))
el.append(Spacer(1, 2 * mm))
cov = [["Poste", "JH", "Avancement", "JH restants", "Reste à faire"]]
done_jh = 0.0; rest_jh = 0.0; rest_cost = 0.0
for name, jh, cost, _, _, _ in rows:
    pg, todo = PROGRESS[name]
    r = jh * (1 - pg)
    done_jh += jh * pg; rest_jh += r; rest_cost += cost * (1 - pg)
    cov.append([P(name), jh, f"{int(pg * 100)} %", f"{r:.0f}", P(todo)])
cov.append([P("Gestion de projet transversale"), pm_jh, "-", f"{pm_jh * 0.6:.0f}", P("Pilotage jusqu'à la mise en production")])
rest_jh += pm_jh * 0.6; rest_cost += pm_cost * 0.6
cov.append(["Total", build_jh, f"{100 * done_jh / build_jh:.0f} %", f"{rest_jh:.0f}", P(f"Reste à faire ≈ {tnd(rest_cost)} TND HT avant aléas")])
el.append(table(cov, [48 * mm, 12 * mm, 20 * mm, 20 * mm, 80 * mm], align_right=(1, 2, 3), total_last=True))
el.append(Spacer(1, 2 * mm))
el.append(P("Le reste à faire est dominé par des activités qui dépendent d'acteurs externes et non du développement : connexion aux trois opérateurs réels, "
            "recette opérateur, validation de la haute disponibilité sur l'infrastructure cible, test d'intrusion et formation.", B))
el.append(P("Principaux risques et dépendances", H2))
for t in ["Délais opérateurs (contrats, VPN, comptes SMPP de test, attribution de short codes) : jusqu'à plusieurs mois, non maîtrisés par le prestataire.",
          "Formats de DLR et de relevés de facturation propres à chaque opérateur, connus tardivement : couverts en partie par l'aléa de 10 %.",
          "Cadre réglementaire des SVA (INT, consentement, conservation) à valider juridiquement avant ouverture commerciale ; l'application bloque les services réglementés non approuvés.",
          "Portabilité des numéros : le routage MT ne doit pas reposer sur le seul préfixe MSISDN ; fournir la source de vérité opérateur."]:
    el.append(P("• " + t, B)); el.append(Spacer(1, 1.2 * mm))

doc.build(el, onFirstPage=footer, onLaterPages=footer)
print(f"PDF : {OUT}\nJH build={build_jh} build_ht={build_ht} conting={conting} total_v1_ht={total_v1_ht} maint={maint} host_month={host_month} tco3={tco3}")

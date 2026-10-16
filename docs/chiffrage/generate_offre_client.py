#!/usr/bin/env python3
"""Chiffrage de RÉALISATION COMPLÈTE en 2 mois (8 semaines) - plateforme VAS / SMS Premium SMPP v3.4 - marché tunisien.
Usage : python3 generate_chiffrage_2mois.py [sortie.pdf]. Taux, capacités, lots et hypothèses sont des paramètres de ce fichier."""
import sys
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

pdfmetrics.registerFont(TTFont("DV", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
pdfmetrics.registerFont(TTFont("DVB", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"))
OUT = sys.argv[1] if len(sys.argv) > 1 else "Offre_commerciale_VAS_SMPP_Tunisie.pdf"

WEEKS = 8
TVA = 0.19
CONTINGENCY = 0.05
REF = "[N° d'offre]"
DATE = "10/10/2026"
ORDER = ["ARCHI", "SDEV", "DEV", "DEVOPS", "QA", "PM", "UX"]
LABEL = {"ARCHI": "Architecte / Tech lead", "SDEV": "Développeur backend senior", "DEV": "Développeur confirmé", "DEVOPS": "Ingénieur DevOps / SRE",
         "QA": "Ingénieur QA / test", "PM": "Chef de projet", "UX": "UX / UI designer"}
RATES = dict(ARCHI=900, SDEV=700, DEV=550, DEVOPS=700, QA=500, PM=800, UX=550)   # TND HT / jour-homme, milieu de fourchette du marché tunisien
MARKET = dict(ARCHI="900 - 1 400", SDEV="500 - 900", DEV="450 - 650")

LOTS = [
 ("1", "Cadrage et paramétrage opérateurs", dict(ARCHI=3, SDEV=2, PM=3), {1}, "Ateliers avec chaque opérateur ; fiche de paramétrage (compte SMPP, débit, règle de facturation sur accusé, short codes) ; plan de recette ; prérequis d'infrastructure.", "Fiche de paramétrage validée, plan de recette"),
 ("2", "Déploiement préproduction et production", dict(ARCHI=1, DEVOPS=8), {1, 2, 7}, "Pile conteneurisée (application, PostgreSQL, Redis, RabbitMQ, Jasmin), TLS, gestion des secrets et de la clé de chiffrement, sauvegardes chiffrées avec test de restauration, supervision, alertes et haute disponibilité de base.", "Environnements opérationnels, procédure de déploiement, sauvegarde testée"),
 ("3", "Intégration et recette Tunisie Telecom", dict(SDEV=5, DEVOPS=2, QA=3), {2, 3}, "Provisionnement de la passerelle SMPP, bind, envoi et réception, formats d'accusés de livraison réels, règles de facturation, jeux de tests opérateur.", "PV de recette opérateur n° 1"),
 ("4", "Intégration et recette Orange Tunisie", dict(SDEV=4, DEVOPS=1, QA=2), {3, 4}, "Même démarche en réutilisant le socle validé avec le premier opérateur ; adaptations propres à l'opérateur.", "PV de recette opérateur n° 2"),
 ("5", "Intégration et recette Ooredoo Tunisie", dict(SDEV=4, DEVOPS=1, QA=2), {4, 5}, "Idem.", "PV de recette opérateur n° 3"),
 ("6", "Données réelles : plages, portabilité, relevés", dict(SDEV=5, DEV=4), {3, 4, 5}, "Chargement des plages de numéros et des numéros portés fournis par les opérateurs ; adaptation du rapprochement aux relevés de facturation réels (mapping de colonnes, formats).", "Routage et rapprochement validés sur données réelles"),
 ("7", "Paramétrage métier et finitions", dict(DEV=6, UX=2, PM=1), {4, 5, 6}, "Création des services initiaux, tarifs et répartitions, textes des réponses en français et en arabe, identité visuelle, comptes et rôles, partenaires et clés d'accès.", "Plateforme paramétrée aux couleurs et règles du client"),
 ("8", "Tests de charge, recette client et corrections", dict(SDEV=4, DEV=3, QA=7, DEVOPS=2), {5, 6, 7}, "Test de charge sur l'infrastructure fournie, recette utilisateur sur scénarios convenus, correction des anomalies, rapport de tests.", "Rapport de tests, PV de recette signé"),
 ("9", "Revue de sécurité et durcissement", dict(ARCHI=1, SDEV=2, DEVOPS=2), {6, 7}, "Revue des configurations de production, des accès et des secrets, rotation des clés, préparation de l'audit externe (périmètre, comptes de test).", "Rapport de revue, check-list de durcissement"),
 ("10", "Formation, documentation, transfert", dict(SDEV=1, DEVOPS=1, QA=1, PM=2), {7, 8}, "Deux jours de formation (exploitation, support, finance), documentation tenue à jour, procédures d'exploitation, transfert des accès et des secrets.", "Supports de formation, PV de transfert"),
 ("11", "Mise en production et suivi renforcé", dict(SDEV=4, DEVOPS=4, PM=1), {7, 8}, "Bascule en production, surveillance rapprochée pendant deux semaines, correction à chaud, point de clôture.", "Production stable, PV de mise en production"),
 ("13", "Options : USSD, IVR, DCB, WhatsApp / RCS, serveur SMPP, antifraude, BI et entrepôt, applications mobiles", dict(SDEV=16, DEV=6, DEVOPS=4, QA=12, UX=3), {5, 6, 7, 8},
  "Modules déjà développés et testés : raccordement à la passerelle USSD de chaque opérateur et paramétrage des menus ; installation du serveur vocal (Asterisk, pont fourni) et des voix ; adaptation de l'adaptateur de paiement à l'API DCB de chaque opérateur ; compte WhatsApp Business / agent RCS, webhooks signés et modèles ; ouverture du serveur SMPP aux partenaires (accès, VPN, recette avec chacun) ; calibrage des seuils et du scoring antifraude ; entrepôt de données et branchement de l'outil décisionnel du client ; applications Android / iOS aux couleurs du client et accompagnement de la publication sur les stores ; recette de chaque option disponible.",
  "PV de recette par option activée"),
 ("12", "Pilotage de projet", dict(PM=6), set(range(1, 9)), "Comité hebdomadaire, suivi des risques et des dépendances opérateurs, rapport d'avancement.", "Comptes rendus hebdomadaires"),
]
EXT_INCLUDED = [("Environnement de préproduction hébergé : 3 machines virtuelles pendant 2 mois", 3 * 304 * 2, "Référence : 304 TND/mois par machine de taille intermédiaire (tarif public d'un hébergeur tunisien) - refacturé au coût réel"),
                ("Déplacements et ateliers sur site (opérateurs, client) - forfait plafonné", 1500, "Sur justificatifs, plafonné au forfait")]
ENV = [("Application ×2", 608), ("PostgreSQL : primaire et réplica", 1216), ("RabbitMQ + Redis", 304), ("Passerelle SMPP ×2", 304), ("Supervision et journaux", 304), ("Stockage des sauvegardes", 150)]

def tnd(x): return f"{x:,.0f}".replace(",", " ")
JH_BY = {p: sum(l[2].get(p, 0) for l in LOTS) for p in ORDER}
rows = [(n, t, sum(d.values()), sum(RATES[p] * j for p, j in d.items()), d, wk, c, liv) for n, t, d, wk, c, liv in LOTS]
JH = sum(r[2] for r in rows); labor = sum(r[3] for r in rows)
conting = round(labor * CONTINGENCY); ext_inc = sum(v for _, v, _ in EXT_INCLUDED)
total_ht = labor + conting + ext_inc; tva = round(total_ht * TVA); total_ttc = total_ht + tva
prod_month = sum(v for _, v in ENV); prod_year = prod_month * 12
PENTEST = 12000; TLS = 300
maint = round((labor + conting) * 0.18)
# Achats à la charge du client : (désignation, unique, annuel, quand commander, base et remarques) - estimations HT à confirmer par devis
BUY = [("Hébergement de production en haute disponibilité (7 machines virtuelles)", 0, prod_year, "J+30 (avant mise en production)",
        "Détail mensuel : " + "; ".join(f"{a} {tnd(b)}" for a, b in ENV) + ". Référence : 152 à 304 TND/mois par machine (hébergeur tunisien ATI, annuaire WHTop). À dimensionner par devis."),
       ("Adresses IP publiques fixes (2) et bande passante", 0, 480, "J+30", "Estimation 20 TND/mois par adresse ; souvent incluses chez l'hébergeur."),
       ("Stockage hors site des sauvegardes chiffrées (≈ 100 Go)", 0, 360, "J+30", "Estimation 30 TND/mois ; chez un second hébergeur ou site du client."),
       ("Nom de domaine et certificat TLS", 0, TLS, "J+5", "Estimation ; certificat gratuit possible."),
       ("Messagerie d'envoi des alertes (service SMTP)", 0, 360, "J+30", "Estimation 30 TND/mois ; peut être le serveur de messagerie existant du client."),
       ("Serveur vocal IVR (Asterisk) : 1 machine virtuelle", 0, 152 * 12, "Semaine 5 (si IVR)", "Référence 152 TND/mois (petite machine, même hébergeur) ; uniquement si l'option IVR est activée."),
       ("Comptes développeur : Google Play (25 USD, une fois) et Apple Developer (99 USD / an)", 80, 310, "Semaine 6 (si applications natives)", "Tarifs publics Google et Apple ; conversion indicative (1 USD ≈ 3,1 TND). Comptes au nom du client."),
       ("Cartes SIM de test (une par opérateur) et crédit de test", 150, 0, "J+5", "Estimation ; sert aux essais de bout en bout et à la recette opérateur."),
       ("Audit de sécurité / test d'intrusion par un auditeur indépendant (certifié ANSI selon votre statut)", PENTEST, 0, "Semaine 6 (réservation dès J+10)",
        "Fourchette de 6 000 à 18 000 pour un test d'application web à Tunis (blog Kolonell, source unique) ; retenu 12 000. L'obligation d'audit périodique dépend de votre statut : à vérifier.")]
buy_once = sum(b[1] for b in BUY); buy_year = sum(b[2] for b in BUY)
reserve = round(buy_once * 0.1) + round(buy_year * 0.1)
y1 = total_ht + buy_once + buy_year + reserve

ss = getSampleStyleSheet()
B = ParagraphStyle("B", parent=ss["Normal"], fontName="DV", fontSize=8.6, leading=11.5)
S = ParagraphStyle("S", parent=B, fontSize=7.4, leading=9.5)
H1 = ParagraphStyle("H1", parent=B, fontName="DVB", fontSize=14, leading=18, spaceBefore=6, spaceAfter=6, textColor=colors.HexColor("#0b3d91"))
H2 = ParagraphStyle("H2", parent=B, fontName="DVB", fontSize=10.5, leading=14, spaceBefore=8, spaceAfter=4, textColor=colors.HexColor("#0b3d91"))
T = ParagraphStyle("T", parent=B, fontName="DVB", fontSize=22, leading=28, textColor=colors.HexColor("#0b3d91"))
NOTE = ParagraphStyle("N", parent=B, fontSize=7.8, leading=10.5, textColor=colors.HexColor("#444444"))
P = lambda t, s=S: Paragraph(str(t), s)

def table(data, widths, right=(), total=False, header=True, extra=()):
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    st = [("FONTNAME", (0, 0), (-1, -1), "DV"), ("FONTSIZE", (0, 0), (-1, -1), 7.4), ("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#c9ced6")),
          ("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3)]
    if header: st += [("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0b3d91")), ("TEXTCOLOR", (0, 0), (-1, 0), colors.white), ("FONTNAME", (0, 0), (-1, 0), "DVB")]
    for i in range(2, len(data), 2): st.append(("BACKGROUND", (0, i), (-1, i), colors.HexColor("#f3f5f9")))
    for c in right: st.append(("ALIGN", (c, 0), (c, -1), "RIGHT"))
    if total: st += [("BACKGROUND", (0, -1), (-1, -1), colors.HexColor("#dfe7f7")), ("FONTNAME", (0, -1), (-1, -1), "DVB")]
    t.setStyle(TableStyle(st + list(extra))); return t

def footer(c, d):
    c.saveState(); c.setFont("DV", 7); c.setFillColor(colors.grey)
    c.drawString(15 * mm, 9 * mm, "Offre commerciale - Plateforme VAS / SMS Premium SMPP v3.4 - Confidentiel")
    c.drawRightString(195 * mm, 9 * mm, f"Page {d.page}"); c.restoreState()

doc = SimpleDocTemplate(OUT, pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm, topMargin=14 * mm, bottomMargin=16 * mm,
                        title="Offre commerciale - Plateforme VAS / SMS Premium SMPP v3.4", author="[Nom du prestataire]")
el = [Spacer(1, 18 * mm), P("OFFRE COMMERCIALE", T), P("Mise en service de la plateforme VAS / SMS Premium - SMPP v3.4", ParagraphStyle("x", parent=H1, fontSize=15)),
      P("Raccordement à Tunisie Telecom, Orange Tunisie et Ooredoo Tunisie", B), Spacer(1, 10 * mm)]
el.append(table([["Client", "[Nom du client]"], ["Offre", f"{REF} - version 1.0 - {DATE}"], ["Émetteur", "[Nom du prestataire], [adresse], [matricule fiscal]"],
                 ["Interlocuteur", "[Nom, fonction, téléphone, courriel]"], ["Validité de l'offre", "30 jours à compter de la date d'émission"],
                 ["Objet", "Cahier des charges technique et fonctionnel V1.0 - plateforme VAS / SMS Premium SMPP v3.4"], ["Nature du prix", "Prix ferme et forfaitaire en dinars tunisiens"]],
                [45 * mm, 135 * mm], header=False))
el += [Spacer(1, 10 * mm), P("Prix de l'offre", H2)]
el.append(table([["Prestations de réalisation (" + str(JH) + " jours-homme, 8 semaines)", f"{tnd(labor)} TND"], [f"Provision pour aléas ({int(CONTINGENCY*100)} %)", f"{tnd(conting)} TND"],
                 ["Achats externes inclus, refacturés au coût réel", f"{tnd(ext_inc)} TND"], ["TOTAL HT", f"{tnd(total_ht)} TND"], [f"TVA {int(TVA*100)} %", f"{tnd(tva)} TND"], ["TOTAL TTC", f"{tnd(total_ttc)} TND"]],
                [120 * mm, 60 * mm], right=(1,), header=False, extra=[("BACKGROUND", (0, 3), (-1, 3), colors.HexColor("#dfe7f7")), ("FONTNAME", (0, 3), (-1, 3), "DVB"), ("FONTNAME", (0, 5), (-1, 5), "DVB")]))
el += [Spacer(1, 4 * mm), P("Ce prix couvre l'intégralité du périmètre décrit en section 2. Les achats externes à la charge du client (hébergement de production, audit de sécurité, "
        "liaisons opérateurs...) sont détaillés dans le document distinct « Achats nécessaires » et ne sont pas inclus dans ce total.", NOTE), PageBreak()]

el.append(P("1. Synthèse", H1))
for t in [f"<b>Ce que nous livrons</b> : une plateforme de services à valeur ajoutée par SMS opérationnelle, raccordée à Tunisie Telecom, Orange Tunisie et Ooredoo Tunisie, "
          "installée en préproduction et en production, recettée avec vos équipes et les trois opérateurs, avec formation, documentation et code source complet.",
          f"<b>Délai</b> : 8 semaines à compter de la commande, sous réserve de la fourniture par chaque opérateur de ses accès (VPN, comptes SMPP, jeux de tests).",
          f"<b>Prix</b> : {tnd(total_ht)} TND HT ({tnd(total_ttc)} TND TTC), forfaitaire. Taux journaliers au milieu de la fourchette du marché tunisien ; coût moyen {tnd(labor / JH)} TND par jour-homme.",
          "<b>Pourquoi ce niveau de prix</b> : la plateforme est déjà développée, testée et documentée ; vous ne payez pas sa conception mais son installation, son raccordement réel, sa recette et son transfert.",
          "<b>Achats nécessaires</b> : hébergement de production, audit de sécurité et autres achats à la charge du client sont présentés à part, dans le document « Achats nécessaires », pour ne pas les confondre avec le prix de l'offre.",
          "<b>Garantie</b> : 3 mois sur les anomalies de la livraison, comprise dans le prix ; maintenance annuelle proposée en option (section 8)."]:
    el += [P("• " + t, B), Spacer(1, 1.5 * mm)]

el.append(P("2. Périmètre et état de la plateforme livrée", H1))
fn = [["Domaine", "Contenu livré"],
      [P("Passerelle SMPP"), P("Raccordement SMPP v3.4 des trois opérateurs via Jasmin, files de messages durables par priorité, reprise automatique, limitation de débit par opérateur / service / partenaire, bascule entre liaisons de secours, accusés de livraison traités sans doublon.")],
      [P("Moteur de services"), P("Mots-clés, consentements avec preuves, STOP / AIDE, abonnements et renouvellements (reprise puis suspension après 3 échecs), vote, quiz, contenu premium par lien limité, campagnes (statistiques, résultats, clôture), français / arabe / anglais, envoi programmé.")],
      [P("Facturation"), P("Journal de facturation sans double comptage, tarifs versionnés approuvés à deux personnes, répartition taxes / opérateur / partenaire / fournisseur, rapprochement CSV et XLSX avec les relevés opérateurs, remboursements et ajustements, clôture de période, relevés et reversements partenaires.")],
      [P("API et intégrations"), P("API REST documentée (OpenAPI), clés et OAuth2, scopes et quotas, webhooks signés protégés contre les détournements, routage par plages de numéros et portabilité.")],
      [P("Back-office et portail"), P("18 écrans en français, arabe (écriture de droite à gauche) et anglais, 7 rôles, portail partenaire isolé, exports PDF / Excel / CSV, conformité d'accessibilité WCAG 2.1 AA vérifiée automatiquement.")],
      [P("Sécurité"), P("Authentification à deux facteurs pour les rôles sensibles, sessions révocables, numéros de téléphone chiffrés en base avec outil de rotation de clé, journal d'audit non modifiable, freinage des tentatives d'authentification, séparation lecture / écriture (architecture CQRS) avec réplica optionnel.")],
      [P("Exploitation"), P("Supervision (métriques, tableaux de bord, alertes), journaux centralisés, sauvegardes chiffrées avec test de restauration, topologie de haute disponibilité de référence, scripts de déploiement.")],
      [P("Options intégrées"), P("USSD (menus interactifs par code *xxx#, session, facturation), serveur vocal IVR (menus DTMF, facturation à la minute, pont Asterisk), paiement sur facture opérateur DCB (code à usage unique, remboursement), WhatsApp et RCS (mêmes mots-clés et services, webhooks signés, modèles approuvés pour les envois à l'initiative du partenaire), serveur SMPP v3.4 complet pour clients externes (bind 3 modes, submit_sm, submit_multi, data_sm, query_sm, cancel_sm, replace_sm, accusés avec TLV rejoués, MO ; matrice de conformité SMPP v3.4 fournie), antifraude (vitesse, plafond de dépense, pics, scoring statistique expliqué), décisionnel et entrepôt de données (ETL nocturne, extractions pour un outil BI), application installable (PWA) et applications natives Android / iOS.")],
      [P("Qualité"), P("140 tests automatisés (122 serveur, 18 interface), scénario d'intégration de bout en bout sur la pile réelle (PostgreSQL, Redis, RabbitMQ, Jasmin), mesures de performance (150 réceptions/s, 186 requêtes API/s sur une instance).")]]
el.append(table(fn, [35 * mm, 145 * mm]))
el.append(Spacer(1, 2 * mm))
el.append(P("<b>Limite à connaître</b> : la plateforme a été validée de bout en bout contre un simulateur d'opérateur, pas encore contre les réseaux réels. C'est précisément l'objet des lots 3 à 5. "
            "Les formats d'accusés de livraison et de relevés de chaque opérateur peuvent demander des adaptations, comprises dans le lot 6. Pour les messages longs (plusieurs parties), "
            "l'accusé final peut se perdre par intermittence selon la passerelle ; une supervision dédiée est fournie et le comportement de chaque opérateur sera qualifié en recette.", NOTE))

el.append(PageBreak())
el.append(P("3. Démarche et planning (8 semaines)", H1))
gd = [["Lot"] + [f"S{w}" for w in range(1, 9)]]
shade = []
for i, (n, t, _, _, _, wk, _, _) in enumerate(rows, start=1):
    gd.append([P(f"{n}. {t}")] + ["" for _ in range(8)])
    for w in wk: shade.append(("BACKGROUND", (w, i), (w, i), colors.HexColor("#4a78c8")))
el.append(table(gd, [78 * mm] + [12.5 * mm] * 8, extra=shade))
el.append(Spacer(1, 3 * mm))
ms = [["Jalon", "Semaine", "Condition"],
      [P("J1 - Cadrage validé"), "S1", P("Fiche de paramétrage et plan de recette signés.")],
      [P("J2 - Premier opérateur raccordé"), "S3", P("Bind SMPP établi, flux de test validés avec l'opérateur.")],
      [P("J3 - Trois opérateurs raccordés"), "S5", P("Procès-verbaux de recette opérateur.")],
      [P("J4 - Recette client"), "S7", P("PV de recette signé, rapport de tests.")],
      [P("J5 - Mise en production"), "S8", P("Bascule réalisée, début du suivi renforcé de 2 semaines.")]]
el.append(table(ms, [60 * mm, 22 * mm, 98 * mm]))
el.append(Spacer(1, 2 * mm))
el.append(P("Les semaines 3 à 5 dépendent de la disponibilité de chaque opérateur. L'ordre des raccordements peut être adapté sans changer le prix ; un retard imputable à un tiers décale le calendrier, pas le montant.", NOTE))

el.append(P("4. Détail du prix par lot", H1))
d = [["Lot", "JH", "Prix HT (TND)", "Contenu", "Livrable"]]
for n, t, j, c, _, _, ct, liv in rows: d.append([P(f"<b>{n}. {t}</b>"), j, tnd(c), P(ct), P(liv)])
d.append([P("Provision pour aléas (5 %)"), "-", tnd(conting), P("Imprévus de réalisation, inclus dans le prix ferme."), ""])
d.append([P("Achats externes inclus (section 6.A)"), "-", tnd(ext_inc), P("Refacturés au coût réel, sans marge."), ""])
d.append([P("TOTAL HT"), JH, tnd(total_ht), P(f"TVA {tnd(tva)} - TTC {tnd(total_ttc)} TND"), ""])
el.append(table(d, [40 * mm, 9 * mm, 22 * mm, 71 * mm, 38 * mm], right=(1, 2), total=True))

el.append(PageBreak())
el.append(P("5. Équipe, taux journaliers et charge", H1))
dd = [["Profil", "JH", "Taux HT (TND/JH)", "Coût HT (TND)", "Repère de marché Tunis 2026"]]
for p in ORDER:
    if JH_BY[p]: dd.append([P(LABEL[p]), JH_BY[p], tnd(RATES[p]), tnd(JH_BY[p] * RATES[p]), P(MARKET.get(p, "-"))])
dd.append(["Total", JH, "", tnd(labor), ""])
el.append(table(dd, [58 * mm, 14 * mm, 30 * mm, 32 * mm, 46 * mm], right=(1, 2, 3), total=True))
el.append(Spacer(1, 2 * mm))
el.append(P("Un jour-homme correspond à un jour ouvré de 8 heures. Les taux sont hors taxes, frais de structure du prestataire inclus. Repères publiés pour Tunis en 2026 (développeurs et architectes) : blog Kolonell, "
            "kolonell.com/fr/blog/tjm-developpeur-application-web-tunis-2026 - source unique et indicative, aucun baromètre officiel n'existant ; les autres profils sont positionnés par comparaison.", NOTE))

el.append(P("6. Achats externes inclus et achats nécessaires", H1))
el.append(P("6.A Inclus dans l'offre - refacturés au coût réel, sans marge", H2))
ea = [["Achat", "Montant HT (TND)", "Base de calcul"]] + [[P(a), tnd(b), P(c)] for a, b, c in EXT_INCLUDED] + [["Total inclus", tnd(ext_inc), ""]]
el.append(table(ea, [75 * mm, 30 * mm, 75 * mm], right=(1,), total=True))
el.append(P("Les achats nécessaires à la charge du client (hébergement de production, audit de sécurité, liaisons opérateurs, etc.) ne font pas partie de ce prix : "
            "ils sont détaillés dans le document distinct <b>« Achats nécessaires »</b> (Achats_necessaires_VAS_SMPP_Tunisie.pdf).", B))
el.append(P("6.B Ce qui n'est pas inclus dans le prix", H2))
ni = [["Élément non inclus", "Qui en a la charge", "Détail ou suite"],
      [P("Hébergement de production en haute disponibilité, adresses IP, bande passante"), P("Client"), P("Document « Achats nécessaires » (estimation).")],
      [P("Stockage hors site des sauvegardes, nom de domaine, certificat TLS, serveur d'envoi des alertes (SMTP)"), P("Client"), P("Document « Achats nécessaires ».")],
      [P("Audit de sécurité / test d'intrusion par un auditeur indépendant (le prestataire prépare le périmètre, lot 9)"), P("Client"), P("Document « Achats nécessaires ».")],
      [P("Cartes SIM de test et crédit de test"), P("Client"), P("Document « Achats nécessaires ».")],
      [P("Liaison ou VPN vers chaque SMSC, comptes SMPP, activation et loyer des numéros courts, tarifs de terminaison des SMS"), P("Opérateurs, selon vos contrats"), P("Montants communiqués par chaque opérateur.")],
      [P("Conseil juridique : protection des données personnelles, conditions d'utilisation, durées de conservation"), P("Client et son conseil"), P("Le prestataire fournit les paramètres techniques.")],
      [P("Préproduction permanente après la livraison (la préproduction du projet est incluse en 6.A)"), P("Client, option"), P("Environ 10 900 TND/an (3 machines).")],
      [P("Maintenance et support après la garantie de 3 mois"), P("Option"), P(f"Environ {tnd(maint)} TND/an, section 8.")],
      [P("Exploitation de la production après le suivi renforcé de 2 semaines"), P("Client (formation incluse)"), P("Reprise possible via la maintenance.")],
      [P("Contrats et frais propres aux options : code USSD, contrat DCB, compte WhatsApp Business (frais de conversation Meta), agent RCS, numéros IVR surtaxés et trunk SIP"), P("Client, opérateurs, Meta"), P("Document « Achats nécessaires ». Une option sans contrat reste désactivée, sans effet sur le prix.")],
      [P("Comptes développeur Google Play et Apple, licence éventuelle d'un outil décisionnel (Power BI…), signature et publication des applications sous le nom du client"), P("Client"), P("Document « Achats nécessaires » ; accompagnement de la publication inclus (lot 13).")],
      [P("Évolutions au-delà du périmètre livré (ex. notifications push natives, antifraude par apprentissage automatique sur historique)"), P("Option"), P("Chiffrables séparément sur demande.")],
      [P("Évolutions hors cahier des charges V1.0, et capacités au-delà de 50 SMS/s par opérateur"), P("Avenant"), P(f"Au taux moyen de l'offre ({tnd(labor / JH)} TND/jour-homme).")],
      [P("Frais de contrats opérateurs et taxes réglementaires éventuelles"), P("Client"), P("Selon vos contrats et votre statut.")]]
el.append(table(ni, [88 * mm, 38 * mm, 54 * mm]))

el.append(Spacer(1, 4 * mm))
el.append(P("7. Responsabilités et prérequis", H1))
rp = [["Élément", "Prestataire", "Client"],
      [P("Réalisation des lots 1 à 12, pilotage, recette technique"), "X", ""],
      [P("Désigner un décideur et valider les livrables sous 3 jours ouvrés"), "", "X"],
      [P("Fournir serveurs, réseau, accès et certificats à J+5"), "", "X"],
      [P("Obtenir auprès des opérateurs les accès VPN, comptes SMPP, numéros courts et jeux de tests"), P("Appui technique"), "X"],
      [P("Données de portabilité et plages de numéros (source opérateur ou régulateur)"), P("Chargement et contrôle"), P("Fourniture")],
      [P("Commander l'audit de sécurité externe et l'hébergement de production"), P("Spécifications"), "X"],
      [P("Conformité juridique (données personnelles, conditions d'utilisation)"), P("Paramétrage technique"), "X"],
      [P("Exploitation de la production après le suivi renforcé"), P("Formation, option de maintenance"), "X"]]
el.append(table(rp, [105 * mm, 40 * mm, 35 * mm]))

el.append(P("8. Conditions commerciales proposées", H1))
cc = [["Condition", "Proposition"],
      [P("Prix"), P("Forfaitaire et ferme pour le périmètre décrit. Toute demande hors périmètre fait l'objet d'un avenant chiffré avant exécution, au taux moyen de l'offre (" + tnd(labor / JH) + " TND/JH).")],
      [P("Paiement"), P("30 % à la commande ; 25 % au premier opérateur raccordé (J2) ; 25 % au PV de recette signé (J4) ; 15 % à la mise en production (J5) ; 5 % à la fin du suivi renforcé. Paiement à 30 jours fin de mois.")],
      [P("Garantie"), P("3 mois à compter de la mise en production : correction sans frais des anomalies de la livraison. Prolongeable via la maintenance.")],
      [P("Maintenance (option)"), P(f"{tnd(maint)} TND HT par an (18 % des prestations) : corrections, mises à jour de sécurité, rapport mensuel, support en heures ouvrées. Astreinte 24/7 sur devis.")],
      [P("Propriété et licences"), P("Code source complet remis au client, droit d'usage et de modification illimité après paiement intégral. Composants tiers sous licences libres (liste fournie), aucune redevance.")],
      [P("Confidentialité et données"), P("Engagement de confidentialité réciproque ; aucune donnée de production n'est copiée hors de l'environnement du client ; données de test fictives.")],
      [P("Délais et retards"), P("8 semaines à compter de la commande et de la réception des prérequis. Un retard imputable à un tiers (opérateur, infrastructure) décale le calendrier sans effet sur le prix.")],
      [P("Recette"), P("Critères : scénarios convenus au lot 1 exécutés sans anomalie bloquante ou majeure ; trois PV opérateur ; rapport de tests. Silence du client 10 jours ouvrés après la mise à disposition = recette tacite.")],
      [P("Validité"), P("30 jours à compter de la date d'émission.")]]
el.append(table(cc, [38 * mm, 142 * mm]))
el.append(Spacer(1, 2 * mm))
el.append(P("Ces conditions sont proposées pour discussion ; elles seront reprises dans le contrat après validation par les services juridiques des deux parties.", NOTE))

el.append(P("9. Hypothèses, exclusions et risques", H1))
rk = [["Point", "Hypothèse ou risque", "Traitement"],
      [P("Accès opérateurs"), P("VPN, comptes et jeux de tests fournis à temps."), P("Raccordements enchaînés selon disponibilité ; calendrier décalé, prix inchangé.")],
      [P("Formats opérateurs"), P("Accusés et relevés pouvant différer du standard."), P("Adaptations incluses (lot 6) ; mappings paramétrables.")],
      [P("Messages longs"), P("Accusé final parfois perdu selon la passerelle."), P("Supervision et alertes fournies ; qualification en recette.")],
      [P("Volumétrie"), P("Jusqu'à 50 SMS/s par opérateur (150 agrégés) en exploitation."), P("Au-delà : montée en charge par ajout d'instances (option).")],
      [P("Périmètre"), P("Cahier des charges V1.0 et toutes ses options (lot 13 : USSD, IVR, DCB, WhatsApp / RCS, serveur SMPP, antifraude, BI et entrepôt, applications mobiles)."), P("Chaque option est activée et recettée dès que son contrat (opérateur, Meta, fournisseur RCS) est disponible.")],
      [P("Options et contrats"), P("Codes USSD, contrats DCB, comptes WhatsApp / RCS et numéros IVR dépendent de tiers ; délais d'obtention souvent supérieurs à 8 semaines."), P("Recette de l'option à la mise à disposition du contrat, sans surcoût, dans les 3 mois suivant la mise en production.")],
      [P("Réglementaire"), P("Évolutions légales sur les services à valeur ajoutée et les données personnelles."), P("Paramètres de conservation et de consentement configurables.")],
      [P("Exclus"), P("Hébergement de production, audit externe, liaisons opérateurs, conseil juridique, frais de contrats opérateurs."), P("Document « Achats nécessaires ».")]]
el.append(table(rk, [38 * mm, 74 * mm, 68 * mm]))

el.append(Spacer(1, 6 * mm))
el.append(P("10. Acceptation de l'offre", H1))
el.append(table([["Pour le client", "Pour le prestataire"], ["Nom : \nFonction : \nDate : \nSignature et cachet :\n\n\n", "Nom : \nFonction : \nDate : \nSignature et cachet :\n\n\n"]], [90 * mm, 90 * mm]))
doc.build(el, onFirstPage=footer, onLaterPages=footer)
print(f"PDF : {OUT}\nJH={JH} MO={labor} aleas={conting} ext_inclus={ext_inc} HT={total_ht} TVA={tva} TTC={total_ttc} moyen={labor/JH:.0f} an1={y1} maint={maint} prod_an={prod_year}")

# ---------- Document 2 : achats nécessaires (distinct de l'offre de prix) ----------
OUT2 = sys.argv[2] if len(sys.argv) > 2 else "Achats_necessaires_VAS_SMPP_Tunisie.pdf"
def footer2(c, d):
    c.saveState(); c.setFont("DV", 7); c.setFillColor(colors.grey)
    c.drawString(15 * mm, 9 * mm, "Achats nécessaires - Plateforme VAS / SMS Premium SMPP v3.4 - Confidentiel - document distinct de l'offre de prix")
    c.drawRightString(195 * mm, 9 * mm, f"Page {d.page}"); c.restoreState()
doc2 = SimpleDocTemplate(OUT2, pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm, topMargin=14 * mm, bottomMargin=16 * mm,
                         title="Achats nécessaires - Plateforme VAS / SMS Premium SMPP v3.4", author="[Nom du prestataire]")
el2 = [Spacer(1, 18 * mm), P("ACHATS NÉCESSAIRES", T), P("À la charge du client - mise en production de la plateforme VAS / SMS Premium", ParagraphStyle("x2", parent=H1, fontSize=14)),
       Spacer(1, 8 * mm)]
el2.append(table([["Référence", f"{REF} - document annexe à l'offre commerciale - {DATE}"], ["Nature", P("Estimations en dinars tunisiens HT, à confirmer par devis des fournisseurs ; ne font pas partie du prix de l'offre", B)],
                  ["Qui achète", P("Le client, directement auprès des fournisseurs (le prestataire fournit les spécifications et accompagne la commande)", B)]], [45 * mm, 135 * mm], header=False))
el2 += [Spacer(1, 6 * mm), P("Ce document est distinct de l'offre de prix. L'offre commerciale (prestations de réalisation, aléas et achats refacturés au coût réel de la préproduction) est chiffrée à "
        f"<b>{tnd(total_ht)} TND HT</b>. Les achats ci-dessous sont à régler par le client aux fournisseurs et opérateurs ; les montants sont des estimations de marché, jamais des engagements du prestataire.", B), Spacer(1, 4 * mm)]
el2.append(P("1. Achats à la charge du client - recommandés", H2))
eb = [["Achat", "Unique (TND)", "Annuel (TND)", "Commande", "Base et remarques"]] + [[P(d), tnd(u) if u else "-", tnd(y) if y else "-", P(w), P(r)] for d, u, y, w, r in BUY]
eb += [[P("Réserve pour écarts de devis (10 %)"), tnd(round(buy_once * 0.1)), tnd(round(buy_year * 0.1)), "", P("Couvre l'écart entre estimations et devis réels.")],
       ["Total achats client (HT)", tnd(buy_once + round(buy_once * 0.1)), tnd(buy_year + round(buy_year * 0.1)), "", ""]]
el2.append(table(eb, [47 * mm, 18 * mm, 20 * mm, 27 * mm, 68 * mm], right=(1, 2), total=True))
el2.append(P("Autres charges à prévoir par le client, non chiffrées ici : conseil juridique (protection des données personnelles, conditions d'utilisation, durées de conservation - à chiffrer par votre conseil ; le prestataire fournit les paramètres techniques), "
            f"maintenance et support après la garantie (option, environ {tnd(maint)} TND/an, section 8 de l'offre). Aucune licence logicielle à acheter : composants libres, aucune redevance. "
            "Un environnement de préproduction permanent après la livraison est une option (3 machines, environ 10 900 TND/an).", NOTE))
el2.append(P("2. Éléments définis par les opérateurs - montants communiqués par chacun", H2))
ec = [["Élément", "Qui fixe le prix", "Remarque"],
      [P("Liaison ou VPN vers le SMSC de chaque opérateur"), P("Opérateur"), P("Condition d'accès au réseau SMPP.")],
      [P("Comptes SMPP, activation et loyer des numéros courts"), P("Opérateur"), P("Dépend de vos contrats d'accès.")],
      [P("Tarifs de terminaison des SMS, SMS de test"), P("Opérateur"), P("Facturés par l'opérateur selon votre contrat.")],
      [P("Reversements opérateurs sur les services à valeur ajoutée"), P("Contrat opérateur"), P("Paramétrés dans la plateforme (tarifs et répartitions).")],
      [P("Option USSD : attribution et loyer du code *xxx#, accès à la passerelle USSD"), P("Opérateur"), P("Par opérateur et par code.")],
      [P("Option DCB : contrat de paiement sur facture, commission opérateur sur chaque paiement"), P("Opérateur"), P("Commission déduite par l'opérateur ; reversement paramétré dans la plateforme.")],
      [P("Option IVR : numéros surtaxés (80 xx xx xx) et trunk SIP vers le serveur vocal"), P("Opérateur"), P("Par numéro et par minute selon contrat.")],
      [P("Option WhatsApp : frais de conversation (grille Meta par pays et catégorie) ; option RCS : tarif du fournisseur RBM / agrégateur"), P("Meta / fournisseur RCS"), P("Facturés directement au client, hors facture opérateur de l'abonné.")]]
el2.append(table(ec, [75 * mm, 35 * mm, 70 * mm]))
el2.append(Spacer(1, 3 * mm))
by = [["Budget indicatif de la première année", "HT (TND)"], ["Offre commerciale (prestations + achats inclus, section 6.A)", tnd(total_ht)],
      ["Achats client uniques (audit de sécurité, SIM de test)", tnd(buy_once)], ["Achats client récurrents sur 12 mois (hébergement, IP, sauvegardes, domaine, alertes)", tnd(buy_year)],
      ["Réserve pour écarts de devis (10 %)", tnd(reserve)], ["Total indicatif (hors opérateurs, juridique, maintenance)", tnd(y1)]]
el2.append(table(by, [140 * mm, 40 * mm], right=(1,), total=True))
el2.append(Spacer(1, 3 * mm))
el2.append(P("Les prix de référence d'hébergement viennent d'un hébergeur tunisien (annuaire WHTop) et la fourchette d'audit d'un blog tunisien (source unique) ; les autres lignes sont des estimations du prestataire, à remplacer par les devis reçus.", NOTE))
doc2.build(el2, onFirstPage=footer2, onLaterPages=footer2)
print(f"PDF : {OUT2}")

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
OUT = sys.argv[1] if len(sys.argv) > 1 else "Offre_competitive_VAS_SMPP_Tunisie.pdf"

WEEKS = 8
TVA = 0.19
CONTINGENCY = 0.05
ORDER = ["ARCHI", "SDEV", "DEV", "DEVOPS", "QA", "PM", "UX"]
LABEL = {"ARCHI": "Architecte / Tech lead", "SDEV": "Développeur backend senior", "DEV": "Développeur confirmé",
         "DEVOPS": "Ingénieur DevOps / SRE", "QA": "Ingénieur QA / test", "PM": "Chef de projet", "UX": "UX / UI designer"}
# taux journaliers HT (TND) : bas de fourchette du marché (voir section 3), engagement ferme
RATES = dict(ARCHI=800, SDEV=600, DEV=450, DEVOPS=600, QA=420, PM=700, UX=450)
MARKET_LOW = dict(ARCHI=900, SDEV=500, DEV=450)   # bornes basses publiées (grille Tunis 2026)

LOTS = [
 ("1. Cadrage et paramétrage opérateurs", dict(ARCHI=2, SDEV=2, PM=2), {1}, "Ateliers avec chaque opérateur, fiche de paramétrage (SMPP, TPS, règles DLR, short codes), plan de recette."),
 ("2. Déploiement préproduction et production", dict(ARCHI=1, DEVOPS=6), {1, 2, 7}, "Pile conteneurisée, TLS, secrets et clé de chiffrement, sauvegardes GPG, supervision et alertes, haute disponibilité de base."),
 ("3. Intégration et recette Tunisie Telecom", dict(SDEV=4, DEVOPS=1, QA=2), {2, 3}, "Provisionnement Jasmin, bind SMPP, formats d'accusés réels, jeux de tests opérateur."),
 ("4. Intégration et recette Orange Tunisie", dict(SDEV=3, DEVOPS=1, QA=1), {3, 4}, "Réutilise le socle validé avec le premier opérateur."),
 ("5. Intégration et recette Ooredoo Tunisie", dict(SDEV=3, DEVOPS=1, QA=1), {4, 5}, "Idem."),
 ("6. Données réelles : plages, portabilité, relevés", dict(SDEV=4, DEV=3), {3, 4, 5}, "Import des plages de numéros et des numéros portés, adaptation du rapprochement aux relevés réels."),
 ("7. Paramétrage métier et finitions client", dict(DEV=5, UX=1), {4, 5, 6}, "Services initiaux, tarifs, textes des réponses FR/AR, identité visuelle, comptes et rôles."),
 ("8. Tests de charge, recette client et corrections", dict(SDEV=3, DEV=2, QA=5), {5, 6, 7}, "Charge sur l'infrastructure fournie, recette utilisateur, correction des anomalies, PV de recette."),
 ("9. Revue de sécurité et durcissement", dict(ARCHI=1, SDEV=2, DEVOPS=1), {6, 7}, "Revue des configurations, contrôle des accès, rotation de clés, préparation au test d'intrusion."),
 ("10. Formation, documentation, transfert", dict(SDEV=1, DEVOPS=1, QA=1, PM=1), {7, 8}, "2 jours de formation, documentation à jour, PV de transfert."),
 ("11. Mise en production et suivi renforcé (2 semaines)", dict(SDEV=3, DEVOPS=3, PM=1), {7, 8}, "Bascule, surveillance rapprochée, correction à chaud, transfert des accès."),
 ("12. Pilotage de projet", dict(PM=4), set(range(1, 9)), "Comité hebdomadaire, suivi des dépendances opérateurs."),
]
NONLABOR = [("Environnement de préproduction hébergé (2 mois)", 1500), ("Déplacements et ateliers sur site", 1000)]

def tnd(x): return f"{x:,.0f}".replace(",", " ")
JH_BY = {p: sum(l[1].get(p, 0) for l in LOTS) for p in ORDER}
rows = [(n, sum(d.values()), sum(RATES[p] * j for p, j in d.items()), d, wk, c) for n, d, wk, c in LOTS]
JH = sum(r[1] for r in rows); labor = sum(r[2] for r in rows)
conting = round(labor * CONTINGENCY); nonlabor = sum(v for _, v in NONLABOR)
total_ht = labor + conting + nonlabor; tva = round(total_ht * TVA); total_ttc = total_ht + tva

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
    c.drawString(15 * mm, 9 * mm, "Plateforme VAS / SMS Premium SMPP v3.4 - Offre compétitive - Mise en service en 8 semaines - Confidentiel")
    c.drawRightString(195 * mm, 9 * mm, f"Page {d.page}"); c.restoreState()

doc = SimpleDocTemplate(OUT, pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm, topMargin=14 * mm, bottomMargin=16 * mm,
                        title="Offre compétitive - Plateforme VAS SMPP Tunisie", author="Offre")
SCRATCH_HT, PREV_HT = 202020, 69720
el = [Spacer(1, 22 * mm), P("OFFRE COMPÉTITIVE", T), P(f"{tnd(total_ht)} TND HT - prix ferme - en service en 8 semaines", ParagraphStyle("x", parent=H1, fontSize=16)),
      P("Plateforme VAS / SMS Premium - SMPP v3.4 - Tunisie Telecom, Orange Tunisie, Ooredoo Tunisie", B), Spacer(1, 6 * mm),
      P("Le prix est bas parce que la plateforme existe déjà : elle est livrée avec son code source complet, ses 108 tests automatisés, sa documentation et ses scripts de déploiement. "
        "Vous ne payez ni la conception ni le développement : vous payez l'installation, l'intégration avec les trois opérateurs, la recette, la mise en production et la formation.", B), Spacer(1, 7 * mm)]
el.append(table([["Charge", f"{JH} jours-homme sur {WEEKS} semaines"], ["Main-d'œuvre (taux bas du marché)", f"{tnd(labor)} TND"],
                 [f"Aléas ({int(CONTINGENCY*100)} %)", f"{tnd(conting)} TND"], ["Préproduction et déplacements", f"{tnd(nonlabor)} TND"],
                 ["TOTAL HT - PRIX FERME", f"{tnd(total_ht)} TND"], [f"TVA {int(TVA*100)} %", f"{tnd(tva)} TND"], ["TOTAL TTC", f"{tnd(total_ttc)} TND"],
                 ["Coût moyen par jour-homme (hors aléas)", f"{tnd(labor / JH)} TND"]], [105 * mm, 75 * mm], right=(1,), header=False,
                extra=[("BACKGROUND", (0, 4), (-1, 4), colors.HexColor("#dfe7f7")), ("FONTNAME", (0, 4), (-1, 4), "DVB"), ("FONTNAME", (0, 6), (-1, 6), "DVB")]))
el += [Spacer(1, 6 * mm), P("Pourquoi cette offre est la plus compétitive", H2)]
for t in [f"<b>{round(100 * (1 - total_ht / SCRATCH_HT))} % moins cher</b> qu'une réalisation depuis zéro en 2 mois ({tnd(SCRATCH_HT)} TND HT) : le développement est déjà fait.",
          f"<b>Coût moyen de {tnd(labor / JH)} TND par jour</b>, sous la fourchette de 700 à 1 200 TND/jour d'une agence full-stack à Tunis.",
          "<b>Prix ferme et plafonné</b> : aucun dépassement facturé pour le périmètre décrit ; paiement lié à des livrables vérifiables.",
          "<b>Équipe réduite et senior</b> : peu d'intervenants, moins de coordination, délais courts ; travail à distance sauf ateliers opérateurs.",
          "<b>Aucune licence</b> : pile 100 % open source, code source remis, aucun verrouillage.",
          "<b>Garantie de 6 mois</b> sur les anomalies, incluse (3 mois dans nos offres précédentes)."]:
    el += [P("• " + t, B), Spacer(1, 1.2 * mm)]
el += [Spacer(1, 2 * mm), P("Ce que ce prix suppose : la plateforme livrée a été validée de bout en bout contre un simulateur d'opérateur, pas contre un vrai opérateur. "
        "Le calendrier de la recette réelle dépend des accès (VPN, comptes SMPP, jeux de tests) que chaque opérateur doit fournir ; il n'influence pas le prix.", NOTE), PageBreak()]

el.append(P("1. Bordereau de prix par lot", H1))
d = [["Lot", "JH", "Prix HT (TND)", "Contenu"]]
for n, j, c, _, _, ct in rows: d.append([P(n), j, tnd(c), P(ct)])
d.append([P("Aléas (5 % de la main-d'œuvre)"), "-", tnd(conting), P("Provision pour imprévus.")])
d.append([P("Frais non salariaux"), "-", tnd(nonlabor), P(" ; ".join(f"{a} : {tnd(b)}" for a, b in NONLABOR))])
d.append([P("TOTAL HT"), JH, tnd(total_ht), P(f"TVA {tnd(tva)} - TTC {tnd(total_ttc)} TND")])
el.append(table(d, [60 * mm, 11 * mm, 26 * mm, 83 * mm], right=(1, 2), total=True))
el.append(Spacer(1, 4 * mm))
el.append(P("2. Planning sur 8 semaines", H1))
gd = [["Lot"] + [f"S{w}" for w in range(1, 9)]]
shade = []
for i, (n, _, _, _, wk, _) in enumerate(rows, start=1):
    gd.append([P(n)] + ["" for _ in range(8)])
    for w in wk: shade.append(("BACKGROUND", (w, i), (w, i), colors.HexColor("#4a78c8")))
el.append(table(gd, [78 * mm] + [12.5 * mm] * 8, extra=shade))
el.append(Spacer(1, 2 * mm))
el.append(P("Les semaines 3 à 5 dépendent des délais de chaque opérateur ; l'ordre des intégrations peut être adapté à leur disponibilité sans changer le prix.", NOTE))

el.append(PageBreak())
el.append(P("3. Équipe et taux", H1))
dd = [["Profil", "JH", "Taux HT (TND/JH)", "Coût HT (TND)", "Repère de marché Tunis 2026"]]
ref = {"ARCHI": "900 - 1 400 (grille publiée)", "SDEV": "500 - 900 (grille publiée)", "DEV": "450 - 650 (grille publiée)"}
for p in ORDER:
    if JH_BY[p]: dd.append([P(LABEL[p]), JH_BY[p], RATES[p], tnd(JH_BY[p] * RATES[p]), P(ref.get(p, "non publié : estimation interne"))])
dd.append(["Total", JH, "", tnd(labor), ""])
el.append(table(dd, [58 * mm, 14 * mm, 28 * mm, 30 * mm, 50 * mm], right=(1, 2, 3), total=True))
el.append(Spacer(1, 2 * mm))
el.append(P("Les taux sont retenus au bas de la fourchette du marché (développeur senior à 600 TND contre 500 - 900 publiés ; le taux de l'architecte, à 800 TND, est volontairement sous le bas de la grille publiée : 900) "
            "en échange d'un engagement ferme, d'une équipe réduite et d'un volume garanti. Source des fourchettes : article « TJM développeur application web Tunis 2026 », blog Kolonell "
            "(kolonell.com/fr/blog/tjm-developpeur-application-web-tunis-2026), source unique d'une agence, indicative ; aucun baromètre officiel tunisien n'existe. "
            "Les taux des autres profils sont des estimations internes.", NOTE))

el.append(P("4. Positionnement par rapport aux alternatives", H1))
cmp_ = [["Option", "Prix HT (TND)", "Délai", "Remarque"],
        [P("<b>Cette offre : plateforme existante + mise en service</b>"), tnd(total_ht), "8 sem.", P("Code, tests et documentation déjà livrés ; reste l'intégration réelle.")],
        [P("Offre plafonnée précédente (finalisation sur 16 semaines)"), tnd(PREV_HT), "16 sem.", P("Même base, étalée sur plus longtemps, avec une équipe plus petite.")],
        [P("Réalisation complète depuis zéro en 2 mois"), tnd(SCRATCH_HT), "8 sem.", P("9 personnes en parallèle ; coût du développement inclus.")],
        [P("Agence full-stack, 700 - 1 200 TND/jour, même charge de développement"), "≥ 190 000", "variable", P("Estimation : charge de la réalisation depuis zéro (270 JH) au bas de la fourchette d'agence.")]]
el.append(table(cmp_, [70 * mm, 26 * mm, 20 * mm, 64 * mm], right=(1,)))
el.append(Spacer(1, 1.5 * mm))
el.append(P("Remarque : le montant de l'offre précédente (16 semaines, 69 720 TND) est plus élevé que celui-ci pour une durée double parce qu'il intégrait des finalisations fonctionnelles depuis réalisées dans la plateforme.", NOTE))

el.append(P("5. Échéancier de paiement", H1))
mil = [("Commande et démarrage", 0.30), ("Intégration réussie avec le premier opérateur (bind et flux de test validés)", 0.25), ("PV de recette signé", 0.25),
       ("Mise en production et PV de transfert", 0.15), ("Fin du suivi renforcé de 2 semaines", 0.05)]
md = [["Jalon", "%", "Montant HT (TND)", "Montant TTC (TND)"]] + [[P(a), f"{int(p*100)} %", tnd(total_ht * p), tnd(total_ht * p * (1 + TVA))] for a, p in mil] + [["Total", "100 %", tnd(total_ht), tnd(total_ht * (1 + TVA))]]
el.append(table(md, [92 * mm, 16 * mm, 36 * mm, 36 * mm], right=(1, 2, 3), total=True))

el.append(PageBreak())
el.append(P("6. Hors périmètre et options", H1))
opt = [["Poste", "Estimation HT (TND)", "Commentaire"],
       [P("Test d'intrusion externe"), "9 000 - 15 000", P("Prestataire indépendant, sur devis.")],
       [P("Haute disponibilité multi-sites (réplication, cluster, bascule)"), tnd(12 * RATES["DEVOPS"]), P("12 JH DevOps, hors infrastructure.")],
       [P("Test de charge à 200 SMS/s sur l'infrastructure finale"), tnd(6 * RATES["QA"] + 3 * RATES["DEVOPS"]), P("9 JH ; nécessite l'infrastructure cible et les opérateurs.")],
       [P("Maintenance et support après la garantie"), tnd(round(labor / JH * 6)) + " / mois", P("≈ 6 JH par mois ; astreinte 24/7 sur devis.")],
       [P("Évolutions hors périmètre"), tnd(round(labor / JH)) + " / JH", P("Au coût moyen pondéré de cette offre, sur bon de commande.")],
       [P("Hébergement production, VPN, short codes, frais opérateurs, conseil juridique, SMS de test"), "client", P("Facturés directement au client par les tiers.")],
       [P("Licences logicielles"), "0", P("Pile 100 % open source.")]]
el.append(table(opt, [84 * mm, 34 * mm, 62 * mm], right=(1,)))

el.append(P("7. Engagements, hypothèses et risques", H1))
rk = [["Point", "Engagement / risque", "Parade"],
      [P("Prix et périmètre"), P("Prix ferme pour le périmètre ci-dessus ; une demande hors périmètre fait l'objet d'un avenant, jamais d'un dépassement non annoncé."), P("Comité hebdomadaire, backlog arbitré par le client.")],
      [P("Garantie"), P("6 mois sur les anomalies de la livraison, corrections sans frais."), P("Suivi des anomalies avec priorités et délais.")],
      [P("Accès opérateurs (VPN, comptes SMPP, jeux de tests)"), P("Hors du contrôle du prestataire : peut décaler la recette réelle, pas le prix."), P("Intégrations enchaînées selon la disponibilité de chacun.")],
      [P("Formats d'accusés et de relevés propres à chaque opérateur"), P("Adaptations possibles après les premiers essais réels, incluses dans le lot 6."), P("Mappings de colonnes paramétrables.")],
      [P("Messages longs : accusé final perdu par intermittence constaté avec le simulateur"), P("À requalifier avec chaque opérateur en recette ; sans accusé, le message est marqué inconnu et sa facturation contestée."), P("Compteur et alertes dédiés fournis.")],
      [P("Infrastructure client"), P("Serveurs, réseau et certificats mis à disposition à J+5."), P("Liste de prérequis fournie dès le cadrage.")],
      [P("Validité de l'offre"), P("30 jours à compter de sa remise."), P("-")]]
el.append(table(rk, [58 * mm, 72 * mm, 50 * mm]))
doc.build(el, onFirstPage=footer, onLaterPages=footer)
print(f"PDF : {OUT}\nJH={JH} main-d'oeuvre={labor} aleas={conting} non-salarial={nonlabor} HT={total_ht} TVA={tva} TTC={total_ttc} moyen={labor/JH:.0f}")

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
OUT = sys.argv[1] if len(sys.argv) > 1 else "Chiffrage_realisation_2_mois_VAS_SMPP_Tunisie.pdf"

WEEKS = 8
TVA = 0.19
CONTINGENCY = 0.10
ORDER = ["ARCHI", "SDEV", "DEV", "DEVOPS", "QA", "PM", "UX"]
LABEL = {"ARCHI": "Architecte / Tech lead", "SDEV": "Développeur backend senior", "DEV": "Développeur confirmé (backend / front)",
         "DEVOPS": "Ingénieur DevOps / SRE", "QA": "Ingénieur QA / test", "PM": "Chef de projet", "UX": "UX / UI designer"}
# taux journaliers HT (TND) : bas / cible / haut. Sources : voir section 2 (bornes développeurs d'après une grille publiée pour Tunis 2026 ; autres profils : estimation).
RATES = {"bas": dict(ARCHI=800, SDEV=600, DEV=450, DEVOPS=600, QA=420, PM=700, UX=450),
         "cible": dict(ARCHI=900, SDEV=700, DEV=550, DEVOPS=700, QA=500, PM=800, UX=550),
         "haut": dict(ARCHI=1100, SDEV=850, DEV=650, DEVOPS=800, QA=580, PM=950, UX=650)}
# équipe : (nombre de personnes, taux d'occupation)
TEAM = {"ARCHI": (1, 0.8), "SDEV": (2, 1.0), "DEV": (2, 1.0), "DEVOPS": (1, 0.5), "QA": (1, 0.75), "PM": (1, 0.5), "UX": (1, 0.2)}
CAP = {p: round(n * occ * WEEKS * 5) for p, (n, occ) in TEAM.items()}   # JH disponibles sur 8 semaines (5 j/sem.)

# lot, JH par profil, semaines actives, livrables
LOTS = [
 ("1. Cadrage, architecture CQRS, modèle de données", dict(ARCHI=6, SDEV=4, DEVOPS=1, PM=3, UX=2), {1}, "Ateliers, ADR, modèle de données, backlog, environnements de développement."),
 ("2. Socle et gateway SMPP (lot A)", dict(ARCHI=4, SDEV=14, DEV=6, DEVOPS=3, QA=3), {1, 2, 3}, "Jasmin, files RabbitMQ par priorité, DLR idempotent, retry, limiteurs de débit, multi-liens, simulateur SMSC."),
 ("3. Moteur VAS (lot B)", dict(ARCHI=3, SDEV=12, DEV=8, QA=3), {2, 3, 4}, "Mots-clés, consentements, STOP/AIDE, abonnements et renouvellements, FR/AR/EN, listes noire/blanche, envoi programmé."),
 ("4. Moteurs de service et campagnes", dict(SDEV=6, DEV=8, QA=3), {4, 5}, "Vote, quiz, contenu premium par lien, statistiques, résultats, clôture, exports."),
 ("5. Billing (lot C)", dict(ARCHI=3, SDEV=14, DEV=8, QA=4), {3, 4, 5, 6}, "Ledger idempotent, tarifs versionnés (4 yeux), répartition, règles DLR, rapprochement CSV/XLSX, ajustements, clôture, relevés, reversements."),
 ("6. API REST, webhooks, routage (lot D)", dict(ARCHI=2, SDEV=8, DEV=4, QA=2), {3, 4, 5}, "OpenAPI, clés et OAuth2, scopes, quotas Redis, webhooks signés anti-SSRF, routage par plages et portabilité."),
 ("7. CQRS : événements, projections, lecture", dict(ARCHI=5, SDEV=6, DEV=3, DEVOPS=1, QA=2), {3, 4, 5}, "Événements de domaine, modèles de lecture, réconciliation, réplica de lecture, règles d'architecture automatisées."),
 ("8. Sécurité et conformité", dict(ARCHI=3, SDEV=5, DEV=3, DEVOPS=1, QA=2), {4, 5, 6, 7}, "RBAC 7 rôles, MFA, sessions, chiffrement des numéros et rotation de clé, audit immuable, anti-brute-force."),
 ("9. Back-office et portail partenaire (lots E, F)", dict(ARCHI=1, DEV=30, QA=4, UX=6), {3, 4, 5, 6, 7}, "17 écrans, FR / AR (RTL) / EN, accessibilité WCAG 2.1 AA, exports, portail isolé."),
 ("10. Infrastructure et exploitation (lot G)", dict(ARCHI=1, DEVOPS=10, QA=1), {1, 2, 6, 7, 8}, "Conteneurs, Prometheus / Grafana / Loki, alertes, sauvegarde et restauration, topologie HA de référence, CI/CD."),
 ("11. Tests et recette interne", dict(ARCHI=1, SDEV=3, DEV=3, DEVOPS=2, QA=6), {5, 6, 7, 8}, "Intégration sur pile réelle, charge, parcours navigateur, accessibilité, rapport de tests."),
 ("12. Documentation, formation, transfert (lot H)", dict(ARCHI=1, SDEV=2, DEV=2, DEVOPS=1, PM=2), {7, 8}, "HLD / LLD, guides, PRA, rapport de sécurité, 2 jours de formation, PV de transfert."),
 ("13. Pilotage de projet", dict(PM=14), set(range(1, 9)), "Comités hebdomadaires, suivi des risques, coordination avec les opérateurs."),
]
alloc = {p: sum(l[1].get(p, 0) for l in LOTS) for p in ORDER}
rest = {p: CAP[p] - alloc[p] for p in ORDER}
assert all(v >= 0 for v in rest.values()), rest
LOTS.append(("14. Revue de code, intégration continue, aléas courants", {p: v for p, v in rest.items() if v}, set(range(1, 9)), "Revues croisées, correction d'anomalies, arbitrages techniques."))
assert {p: sum(l[1].get(p, 0) for l in LOTS) for p in ORDER} == CAP

NONLABOR = [("Environnement de préproduction hébergé (2 mois)", 3500), ("Déplacements et ateliers sur site (opérateurs, client)", 2500)]

def tnd(x): return f"{x:,.0f}".replace(",", " ")
def cost(rates, d): return sum(rates[p] * j for p, j in d.items())
R = RATES["cible"]
rows = [(n, sum(d.values()), cost(R, d), d, wk, c) for n, d, wk, c in LOTS]
JH = sum(r[1] for r in rows); labor = sum(r[2] for r in rows)
conting = round(labor * CONTINGENCY); nonlabor = sum(v for _, v in NONLABOR)
total_ht = labor + conting + nonlabor; tva = round(total_ht * TVA); total_ttc = total_ht + tva
def scenario(k):
    r = RATES[k]; l = sum(cost(r, d) for _, d, _, _ in LOTS); c = round(l * CONTINGENCY); ht = l + c + nonlabor
    return l, c, ht, round(ht * TVA), ht + round(ht * TVA)

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
    c.drawString(15 * mm, 9 * mm, "Plateforme VAS / SMS Premium SMPP v3.4 - Chiffrage de réalisation en 2 mois - Marché tunisien - Confidentiel")
    c.drawRightString(195 * mm, 9 * mm, f"Page {d.page}"); c.restoreState()

doc = SimpleDocTemplate(OUT, pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm, topMargin=14 * mm, bottomMargin=16 * mm,
                        title="Chiffrage de réalisation en 2 mois - Plateforme VAS SMPP Tunisie", author="Chiffrage")
lo, hi = scenario("bas"), scenario("haut")
el = [Spacer(1, 22 * mm), P("CHIFFRAGE DE RÉALISATION", T), P("Réalisation complète en 2 mois (8 semaines)", ParagraphStyle("x", parent=H1, fontSize=16)),
      P("Plateforme VAS / SMS Premium - SMPP v3.4 - Tunisie Telecom, Orange Tunisie, Ooredoo Tunisie", B), Spacer(1, 6 * mm),
      P("Périmètre : cahier des charges technique et fonctionnel V1.0, tel que décrit dans la spécification fonctionnelle (docs/00) avec architecture CQRS. "
        "Budget établi aux conditions du marché tunisien (taux journaliers de prestataires, TVA 19 %), en dinars tunisiens.", B), Spacer(1, 7 * mm)]
el.append(table([["Charge totale", f"{JH} JH sur {WEEKS} semaines, {sum(n for n, _ in TEAM.values())} personnes"],
                 ["Main-d'œuvre (taux cibles)", f"{tnd(labor)} TND"], [f"Aléas et risques ({int(CONTINGENCY*100)} % de la main-d'œuvre)", f"{tnd(conting)} TND"],
                 ["Frais non salariaux (préproduction, déplacements)", f"{tnd(nonlabor)} TND"],
                 ["TOTAL HT", f"{tnd(total_ht)} TND"], [f"TVA {int(TVA*100)} %", f"{tnd(tva)} TND"], ["TOTAL TTC", f"{tnd(total_ttc)} TND"],
                 ["Fourchette HT selon les taux du marché (bas - haut)", f"{tnd(lo[2])} - {tnd(hi[2])} TND"]], [105 * mm, 75 * mm], right=(1,), header=False,
                extra=[("BACKGROUND", (0, 4), (-1, 4), colors.HexColor("#dfe7f7")), ("FONTNAME", (0, 4), (-1, 4), "DVB"), ("FONTNAME", (0, 6), (-1, 6), "DVB")]))
el += [Spacer(1, 7 * mm), P("Lecture du budget", H2),
       P(f"Livrer l'ensemble du périmètre en 8 semaines impose de paralléliser : l'équipe de {sum(n for n, _ in TEAM.values())} personnes ci-dessous travaille en simultané sur les lots. "
         f"Le coût moyen est de {tnd(labor / JH)} TND par jour-homme (hors aléas), soit sous le bas de la fourchette de 700 à 1 200 TND/jour observée pour une agence "
         "full-stack à Tunis : le prix est donc cohérent avec le marché, sans marge de confort supplémentaire. "
         "Un calendrier plus long avec une équipe plus petite réduirait la part de coordination et d'aléas, sans changer la charge de développement ; "
         "c'est le délai de 2 mois, pas le périmètre, qui impose cette équipe nombreuse.", B), Spacer(1, 3 * mm),
       P("Ce chiffrage est une estimation de l'effort de réalisation d'un prestataire externe. Il ne prend pas en compte le code déjà présent dans le dépôt de ce projet, "
         "et ne couvre pas la recette réelle chez les opérateurs ni la mise en production (voir section 7), qui dépendent de tiers.", NOTE), PageBreak()]

el.append(P("1. Équipe et taux journaliers", H1))
d = [["Profil", "Équipe", "Occup.", "JH", "Taux bas", "Taux cible", "Taux haut", "Coût cible"]]
for p in ORDER:
    n, occ = TEAM[p]
    d.append([P(LABEL[p]), n, f"{int(occ*100)} %", CAP[p], RATES["bas"][p], RATES["cible"][p], RATES["haut"][p], tnd(CAP[p] * R[p])])
d.append(["Total", sum(n for n, _ in TEAM.values()), "", JH, "", "", "", tnd(labor)])
el.append(table(d, [52 * mm, 14 * mm, 20 * mm, 20 * mm, 17 * mm, 17 * mm, 17 * mm, 25 * mm], right=(1, 3, 4, 5, 6, 7), total=True))
el.append(Spacer(1, 3 * mm))
el.append(P("Un jour-homme (JH) = 1 jour ouvré de 8 heures ; 8 semaines = 40 jours ouvrés par personne à 100 %. Les taux sont hors taxes, charges et frais de structure du prestataire inclus.", NOTE))

el.append(P("2. Références de marché", H1))
mk = [["Profil", "Taux cible retenu", "Repère de marché (Tunis, 2026)", "Nature du repère"],
      [P("Architecte / lead"), R["ARCHI"], P("900 - 1 400 TND/jour"), P("Grille publiée (1)")],
      [P("Développeur senior (5 - 8 ans)"), R["SDEV"], P("500 - 900 TND/jour"), P("Grille publiée (1)")],
      [P("Développeur confirmé (2 - 5 ans)"), R["DEV"], P("450 - 650 TND/jour"), P("Grille publiée (1)")],
      [P("Agence full-stack, tout compris"), "-", P("700 - 1 200 TND/jour"), P("Grille publiée (1)")],
      [P("DevOps, QA, chef de projet, UX/UI"), f"{R['DEVOPS']} / {R['QA']} / {R['PM']} / {R['UX']}", P("non publié"), P("Estimation interne, à confirmer par devis")]]
el.append(table(mk, [58 * mm, 30 * mm, 50 * mm, 42 * mm], right=(1,)))
el.append(Spacer(1, 2 * mm))
el.append(P("(1) Source : article « TJM développeur application web Tunis 2026 », blog Kolonell (kolonell.com/fr/blog/tjm-developpeur-application-web-tunis-2026). Source unique d'une agence, à considérer comme indicative : "
            "il n'existe pas de baromètre officiel tunisien des taux journaliers. Les taux doivent être confirmés par au moins deux devis de prestataires avant engagement. "
            "Repère de change indicatif : 1 EUR ≈ 3,35 TND.", NOTE))

el.append(PageBreak())
el.append(P("3. Bordereau de prix par lot", H1))
d = [["Lot", "JH", "Coût HT (TND)", "Contenu"]]
for n, j, c, _, _, ct in rows: d.append([P(n), j, tnd(c), P(ct)])
d.append([P("Aléas et risques (10 % de la main-d'œuvre)"), "-", tnd(conting), P("Provision pour imprévus de réalisation.")])
d.append([P("Frais non salariaux"), "-", tnd(nonlabor), P(" ; ".join(f"{a} : {tnd(b)}" for a, b in NONLABOR))])
d.append([P("TOTAL HT"), JH, tnd(total_ht), P(f"TVA {tnd(tva)} - TTC {tnd(total_ttc)} TND")])
el.append(table(d, [55 * mm, 12 * mm, 28 * mm, 85 * mm], right=(1, 2), total=True))
el.append(PageBreak())
el.append(P("Répartition des jours-homme par lot et par profil", H2))
hd = ["Lot", "ARCHI", "SDEV", "DEV", "DEVOPS", "QA", "PM", "UX", "Total"]
dd = [hd] + [[P(r[0])] + [r[3].get(p, "") for p in ORDER] + [r[1]] for r in rows] + [["Total"] + [CAP[p] for p in ORDER] + [JH]]
el.append(table(dd, [60 * mm] + [15 * mm] * 7 + [15 * mm], right=tuple(range(1, 9)), total=True))
el.append(Spacer(1, 1.5 * mm))
el.append(P("ARCHI = architecte / tech lead · SDEV = développeur senior · DEV = développeur confirmé · DEVOPS · QA · PM = chef de projet · UX = designer.", NOTE))

el.append(Spacer(1, 4 * mm))
el.append(P("4. Planning sur 8 semaines", H1))
gd = [["Lot"] + [f"S{w}" for w in range(1, 9)]]
shade = []
for i, (n, _, _, _, wk, _) in enumerate(rows, start=1):
    gd.append([P(n)] + ["" for _ in range(8)])
    for w in wk: shade.append(("BACKGROUND", (w, i), (w, i), colors.HexColor("#4a78c8")))
el.append(table(gd, [78 * mm] + [12.5 * mm] * 8, extra=shade))
el.append(Spacer(1, 3 * mm))
ms = [["Jalon", "Fin de", "Livrable démontré"],
      ["J1 - Architecture validée", "S1", "ADR (CQRS), modèle de données, backlog priorisé, environnements prêts."],
      ["J2 - Flux MO → MT → DLR", "S3", "Chaîne complète sur simulateur SMSC : réception, réponse, accusé, premier ledger."],
      ["J3 - Cœur métier", "S5", "Moteurs vote / quiz / contenu, abonnements, billing, API et webhooks, modèles de lecture."],
      ["J4 - Back-office complet", "S6", "17 écrans FR / AR / EN, portail partenaire, rapprochement, clôture de période."],
      ["J5 - Sécurité et supervision", "S7", "RBAC, MFA, chiffrement, audit immuable, tableaux de bord et alertes."],
      ["J6 - Livraison V1", "S8", "Recette interne signée, documentation, formation, code source et scripts de déploiement."]]
ms = [ms[0]] + [[P(a) if not hasattr(a, "wrap") else a, b, P(c) if not hasattr(c, "wrap") else c] for a, b, c in ms[1:]]
el.append(table(ms, [48 * mm, 16 * mm, 116 * mm]))

el.append(P("5. Récapitulatif budgétaire", H1))
sc = [["Scénario de taux", "Main-d'œuvre", "Aléas 10 %", "Non salarial", "Total HT", "TVA 19 %", "Total TTC"]]
for k, name in (("bas", "Bas du marché"), ("cible", "Cible (recommandé)"), ("haut", "Haut du marché")):
    l, c, ht, t, ttc = scenario(k)
    sc.append([name, tnd(l), tnd(c), tnd(nonlabor), tnd(ht), tnd(t), tnd(ttc)])
el.append(table(sc, [38 * mm, 26 * mm, 22 * mm, 22 * mm, 25 * mm, 22 * mm, 25 * mm], right=(1, 2, 3, 4, 5, 6), extra=[("BACKGROUND", (0, 2), (-1, 2), colors.HexColor("#dfe7f7")), ("FONTNAME", (0, 2), (-1, 2), "DVB")]))

el.append(PageBreak())
el.append(P("6. Échéancier de paiement proposé", H1))
mil = [("Commande et démarrage", 0.30), ("Jalon J3 : cœur métier démontré (fin S5)", 0.30), ("Jalon J6 : livraison V1 et PV de recette interne (fin S8)", 0.30),
       ("Fin de la garantie de 3 mois (retenue de garantie)", 0.10)]
md = [["Jalon", "%", "Montant HT (TND)", "Montant TTC (TND)"]] + [[a, f"{int(p*100)} %", tnd(total_ht * p), tnd(total_ht * p * (1 + TVA))] for a, p in mil] + [["Total", "100 %", tnd(total_ht), tnd(total_ht * (1 + TVA))]]
el.append(table(md, [92 * mm, 16 * mm, 36 * mm, 36 * mm], right=(1, 2, 3), total=True))
el.append(Spacer(1, 3 * mm))
el.append(P("Le prix est ferme pour le périmètre décrit ; toute évolution fait l'objet d'un avenant au taux moyen pondéré. Garantie de 3 mois sur les anomalies de la livraison, comprise dans le prix.", NOTE))

el.append(P("7. Hors périmètre et options (non incluses dans le total)", H1))
blend = labor / JH
opt = [["Poste", "Estimation HT (TND)", "Commentaire"],
       [P("Intégration et recette chez les 3 opérateurs (bind, formats DLR, relevés réels)"), tnd(3 * (3 * R["SDEV"] + R["DEVOPS"] + R["QA"])), P("15 JH ; dépend des délais, VPN et comptes SMPP fournis par chaque opérateur.")],
       [P("Mise en production et suivi renforcé de 2 semaines"), tnd(4 * R["DEVOPS"] + 4 * R["SDEV"] + 2 * R["PM"]), P("10 JH.")],
       [P("Test d'intrusion externe"), "9 000 - 15 000", P("Prestataire spécialisé indépendant ; estimation à confirmer par devis.")],
       [P("Haute disponibilité multi-sites (réplication PostgreSQL, cluster RabbitMQ, bascule)"), tnd(12 * R["DEVOPS"]), P("12 JH DevOps ; hors coûts d'infrastructure.")],
       [P("Test de charge à 200 SMS/s sur l'infrastructure finale"), tnd(6 * R["QA"] + 3 * R["DEVOPS"]), P("9 JH ; nécessite l'infrastructure cible.")],
       [P("Maintenance et support (après la garantie)"), tnd(round(blend * 8)) + " / mois", P("≈ 8 JH par mois : corrections, sécurité, rapport mensuel. Astreinte 24/7 sur devis.")],
       [P("Hébergement production (HA + préproduction)"), "devis hébergeur", P("À la charge du client.")],
       [P("Frais opérateurs, VPN, short codes, conseil juridique, SMS de test"), "client", P("Facturés directement au client par les tiers.")],
       [P("Licences logicielles"), "0", P("Pile 100 % open source.")]]
el.append(table(opt, [82 * mm, 34 * mm, 64 * mm], right=(1,)))

el.append(P("8. Hypothèses, dépendances et risques", H1))
rk = [["Risque / hypothèse", "Effet", "Parade"],
      [P("Équipe complète disponible dès le jour 1 et stable pendant 8 semaines"), P("Chaque absence prolongée décale le planning (parallélisation maximale)."), P("Binômes sur les lots critiques ; aléas de 10 %.")],
      [P("Périmètre figé : cahier des charges V1 et spécification fonctionnelle"), P("Toute évolution consomme les aléas puis donne lieu à avenant."), P("Comité de pilotage hebdomadaire ; backlog arbitré par le client.")],
      [P("Validation métier du client sous 3 jours ouvrés"), P("Un retard bloque les lots dépendants."), P("Décideur désigné dès le cadrage.")],
      [P("Accès aux opérateurs (paramètres SMPP, VPN, jeux de test)"), P("Hors du contrôle du prestataire : peut retarder la recette réelle, pas le développement."), P("Développement sur simulateur SMSC ; recette opérateur planifiée à part.")],
      [P("Formats d'accusés et de relevés propres à chaque opérateur"), P("Adaptations après les premiers essais réels."), P("Mapping de colonnes paramétrable ; temps d'ajustement dans l'option d'intégration.")],
      [P("Évolution réglementaire (protection des données, SMS à valeur ajoutée)"), P("Possibles exigences de conservation ou de consentement."), P("Durées de conservation paramétrables ; avis juridique du client.")]]
el.append(table(rk, [66 * mm, 62 * mm, 52 * mm]))
doc.build(el, onFirstPage=footer, onLaterPages=footer)
print(f"PDF : {OUT}\nJH={JH} main-d'oeuvre={labor} aleas={conting} non-salarial={nonlabor} HT={total_ht} TVA={tva} TTC={total_ttc}")
print("fourchette HT:", lo[2], hi[2], "| capacités", CAP, "| marge technique", rest)

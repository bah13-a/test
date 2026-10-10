#!/usr/bin/env python3
"""Chiffrage de RÉALISATION plafonné (offre <= 70 000 TND HT) : livraison de la V1 à partir de la plateforme déjà développée (dépôt).
Usage : python3 generate_chiffrage_realisation.py [sortie.pdf]. Les JH, taux et le plafond sont des paramètres de ce fichier."""
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
OUT = sys.argv[1] if len(sys.argv) > 1 else "Chiffrage_realisation_70k_VAS_SMPP_Tunisie.pdf"

CAP = 70000          # plafond demandé (TND HT)
CONTINGENCY = 0.05   # aléas, inclus dans le plafond
TVA = 0.19
RATES = {"ARCHI": 900, "SDEV": 700, "DEV": 550, "DEVOPS": 700, "QA": 500, "PM": 800, "UX": 550}
LABEL = {"ARCHI": "Architecte / Tech lead", "SDEV": "Développeur senior", "DEV": "Développeur", "DEVOPS": "Ingénieur DevOps/SRE",
         "QA": "Ingénieur QA / test", "PM": "Chef de projet", "UX": "UX/UI designer"}
ORDER = ["ARCHI", "SDEV", "DEV", "DEVOPS", "QA", "PM", "UX"]

LINES = [  # poste, JH par profil, délai, contenu
 ("Cadrage, ateliers opérateurs, collecte des paramètres", dict(ARCHI=3, PM=2, SDEV=2), "2 sem.", "Atelier par opérateur, fiche de paramétrage (Annexe B), validation du HLD fourni."),
 ("Intégration & recette Tunisie Telecom", dict(SDEV=4, DEVOPS=2, QA=2), "4 sem.", "Provisionnement Jasmin, bind, formats DLR, jeux de tests opérateur. Dépend des délais TT."),
 ("Intégration & recette Orange Tunisie", dict(SDEV=3, DEVOPS=1, QA=1), "3 sem.", "Réutilise le socle TT."),
 ("Intégration & recette Ooredoo Tunisie", dict(SDEV=3, DEVOPS=1, QA=1), "3 sem.", "Réutilise le socle TT."),
 ("Finalisation du moteur VAS (quiz, premium, vote, campagnes)", dict(SDEV=5, DEV=7, QA=3), "4 sem.", "Quiz à questions, liens/jetons premium, choix de vote valides, clôture et résultats de campagne."),
 ("Billing & rapprochement : formats réels, ajustements, reversements", dict(SDEV=5, DEV=4, QA=2), "3 sem.", "Relevés opérateur réels, remboursements/ajustements, clôture de période, relevés partenaires."),
 ("Back-office, portail, API : finitions", dict(SDEV=3, DEV=5, UX=2, QA=2), "3 sem.", "Pagination, modification des tarifs/services, i18n complète, accessibilité, OAuth2."),
 ("Sécurité & durcissement", dict(ARCHI=1, SDEV=3, DEVOPS=2), "2 sem.", "Mots de passe/sessions, audit immuable, chiffrement des MSISDN, revue de sécurité interne."),
 ("Infrastructure, supervision, sauvegardes (préprod + prod)", dict(DEVOPS=7, ARCHI=1), "3 sem.", "Déploiement de la pile, TLS, alertes, sauvegardes GPG, test de restauration."),
 ("Tests d'intégration, de charge (50 SMS/s/opérateur) et recette", dict(QA=5, DEVOPS=2, SDEV=1), "3 sem.", "Pile réelle, recette UAT avec le client, PV de recette."),
 ("Documentation (mise à jour) & formation 2 jours", dict(PM=1, SDEV=2, DEVOPS=1, QA=1), "1 sem.", "Documentation livrée tenue à jour, 2 jours de formation, PV de transfert."),
 ("Mise en production & hypercare 2 semaines", dict(DEVOPS=3, SDEV=3, PM=1), "3 sem.", "Go-live, 2 semaines de suivi renforcé, transfert des accès."),
 ("Gestion de projet", dict(PM=4), "continu", "Pilotage, COPIL, suivi des dépendances opérateurs."),
]

def tnd(x): return f"{x:,.0f}".replace(",", " ")
rows = [(n, sum(d.values()), sum(RATES[p] * j for p, j in d.items()), dl, c, d) for n, d, dl, c in LINES]
base = sum(r[2] for r in rows); jh = sum(r[1] for r in rows)
conting = min(round(base * CONTINGENCY), CAP - base)
total = base + conting
assert total <= CAP, f"dépasse le plafond : {total} > {CAP}"

ss = getSampleStyleSheet()
B = ParagraphStyle("B", parent=ss["Normal"], fontName="DV", fontSize=8.6, leading=11.5)
S = ParagraphStyle("S", parent=B, fontSize=7.4, leading=9.5)
H1 = ParagraphStyle("H1", parent=B, fontName="DVB", fontSize=14, leading=18, spaceBefore=6, spaceAfter=6, textColor=colors.HexColor("#0b3d91"))
H2 = ParagraphStyle("H2", parent=B, fontName="DVB", fontSize=10.5, leading=14, spaceBefore=8, spaceAfter=4, textColor=colors.HexColor("#0b3d91"))
T = ParagraphStyle("T", parent=B, fontName="DVB", fontSize=22, leading=28, textColor=colors.HexColor("#0b3d91"))
NOTE = ParagraphStyle("N", parent=B, fontSize=7.8, leading=10.5, textColor=colors.HexColor("#444444"))
P = lambda t, s=S: Paragraph(str(t), s)

def table(data, widths, right=(), total=False, header=True):
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    st = [("FONTNAME", (0, 0), (-1, -1), "DV"), ("FONTSIZE", (0, 0), (-1, -1), 7.4), ("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#c9ced6")),
          ("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3)]
    if header: st += [("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0b3d91")), ("TEXTCOLOR", (0, 0), (-1, 0), colors.white), ("FONTNAME", (0, 0), (-1, 0), "DVB")]
    for i in range(2, len(data), 2): st.append(("BACKGROUND", (0, i), (-1, i), colors.HexColor("#f3f5f9")))
    for c in right: st.append(("ALIGN", (c, 0), (c, -1), "RIGHT"))
    if total: st += [("BACKGROUND", (0, -1), (-1, -1), colors.HexColor("#dfe7f7")), ("FONTNAME", (0, -1), (-1, -1), "DVB")]
    t.setStyle(TableStyle(st)); return t

def footer(c, d):
    c.saveState(); c.setFont("DV", 7); c.setFillColor(colors.grey)
    c.drawString(15 * mm, 9 * mm, "Plateforme VAS / SMS Premium SMPP v3.4 - Chiffrage de réalisation plafonné - Confidentiel")
    c.drawRightString(195 * mm, 9 * mm, f"Page {d.page}"); c.restoreState()

doc = SimpleDocTemplate(OUT, pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm, topMargin=14 * mm, bottomMargin=16 * mm,
                        title="Chiffrage de réalisation plafonné - Plateforme VAS SMPP Tunisie", author="Réponse à consultation")
el = [Spacer(1, 25 * mm), P("CHIFFRAGE DE RÉALISATION", T), P("Offre plafonnée à 70 000 TND HT", ParagraphStyle("x", parent=H1, fontSize=16)),
      P("Plateforme VAS / SMS Premium - SMPP v3.4 - Tunisie Telecom, Orange Tunisie, Ooredoo Tunisie", B), Spacer(1, 6 * mm),
      P("Réponse financière à la consultation « Cahier des charges technique &amp; fonctionnel V1.0 » du 05/10/2026.", B), Spacer(1, 8 * mm)]
el.append(table([["Réalisation V1 - charge", f"{jh} JH"], ["Coût de réalisation (HT)", f"{tnd(base)} TND"], [f"Aléas ({int(CONTINGENCY*100)} %, inclus dans le plafond)", f"{tnd(conting)} TND"],
                 ["TOTAL RÉALISATION HT", f"{tnd(total)} TND"], ["TOTAL RÉALISATION TTC (TVA 19 %)", f"{tnd(total * (1 + TVA))} TND"], ["Plafond demandé (HT)", f"{tnd(CAP)} TND"],
                 ["Marge sous le plafond", f"{tnd(CAP - total)} TND"]], [110 * mm, 70 * mm], right=(1,), header=False))
el += [Spacer(1, 8 * mm), P("Cette offre s'appuie sur la plateforme VAS déjà développée et testée (code source complet remis au client) : le prix couvre la finalisation, "
       "l'intégration avec les trois opérateurs, la recette, la mise en production et le transfert - pas un développement depuis zéro. "
       "Elle est ferme sous réserve des hypothèses et exclusions des sections 2 et 3 et de la fourniture des paramètres opérateurs (Annexe B du cahier des charges).", NOTE), PageBreak()]

el.append(P("1. Bordereau de prix de réalisation", H1))
d = [["Poste", "JH", "Prix HT (TND)", "Délai", "Contenu"]]
for n, j, c, dl, ct, _ in rows: d.append([P(n), j, tnd(c), dl, P(ct)])
d.append([P("Aléas et risques"), "-", tnd(conting), "-", P("Provision incluse dans le plafond.")])
d.append([P("<b>TOTAL RÉALISATION HT</b>"), jh, tnd(total), "≈ 16 sem.", P(f"TTC : {tnd(total * (1 + TVA))} TND. Plafond : {tnd(CAP)} TND HT.")])
el.append(table(d, [55 * mm, 11 * mm, 23 * mm, 16 * mm, 75 * mm], right=(1, 2), total=True))
el.append(Spacer(1, 3 * mm))
el.append(P("Détail par profil", H2))
tot = {p: sum(r[5].get(p, 0) for r in rows) for p in ORDER}
dd = [["Profil", "Taux (TND HT/JH)", "JH", "Coût HT (TND)"]] + [[LABEL[p], tnd(RATES[p]), tot[p], tnd(tot[p] * RATES[p])] for p in ORDER if tot[p]]
dd.append(["Total", "", jh, tnd(base)])
el.append(table(dd, [80 * mm, 35 * mm, 25 * mm, 40 * mm], right=(1, 2, 3), total=True))

el.append(PageBreak())
el.append(P("2. Périmètre inclus et hypothèses", H1))
for t in ["Plateforme livrée : gateway SMPP (Jasmin) + moteur VAS + billing/ledger + rapprochement + API REST + back-office + portail partenaire, profils dev (mocks) et pro (données réelles), documentation, tests automatisés et scripts de déploiement/sauvegarde - code source complet au client.",
          "Intégration, paramétrage et recette avec Tunisie Telecom, Orange Tunisie et Ooredoo Tunisie, 50 SMS/s par opérateur, jusqu'à 200 SMS/s agrégés par extension de capacité.",
          "Environnements préproduction et production déployés sur l'infrastructure fournie par le client ; HA de base (réplication PostgreSQL, files RabbitMQ répliquées, 2 instances applicatives).",
          "Formation de 2 jours, PV de transfert, 2 semaines d'hypercare après mise en production.",
          "Le client fournit : paramètres SMPP et jeux de tests de chaque opérateur, VPN, interlocuteurs NOC, infrastructure, nom de domaine et certificat TLS, validation métier sous 5 jours ouvrés.",
          "Taux journaliers : hypothèses de marché (section 1) ; durée d'environ 16 semaines hors délais opérateurs, qui conditionnent le calendrier réel."]:
    el += [P("• " + t, B), Spacer(1, 1.5 * mm)]

el.append(P("3. Hors plafond (non inclus dans les 70 000 TND)", H1))
maint = round(total * 0.18); host = 2590
out = [["Poste", "Montant HT (TND)", "Commentaire"],
       [P("Maintenance annuelle (18 % de la réalisation)"), tnd(maint) + " / an", P("Corrective, sécurité, rapport mensuel, SLA du cahier des charges §16.")],
       [P("Support 24/7 optionnel"), "36 000 / an", P("Astreinte P1/P2 24/7.")],
       [P("Hébergement (estimatif)"), tnd(host) + " / mois", P("Prod HA + préprod + dev ; devis de l'hébergeur retenu.")],
       [P("Licences tierces"), "0", P("100 % open source (voir docs/09-licences.md).")],
       [P("Options (USSD, DCB, IVR, RCS/WhatsApp, BI, antifraude, appli mobile, serveur SMPP)"), "sur devis", P("Chiffrées séparément (document de chiffrage complet, section 6).")],
       [P("Test d'intrusion externe"), "sur devis", P("Prestataire spécialisé indépendant recommandé.")],
       [P("Test de charge à 200 SMS/s sur l'infrastructure finale"), "sur devis", P("Nécessite l'infrastructure cible et la participation des opérateurs.")],
       [P("Frais opérateurs (activation short code, VPN, abonnements), conseil juridique, SMS de test"), "client", P("Facturés directement au client par les tiers.")],
       [P("Évolutions hors périmètre"), "680 / JH", P("Au taux moyen pondéré, sur bon de commande.")]]
el.append(table(out, [78 * mm, 32 * mm, 70 * mm], right=(1,)))

el.append(Spacer(1, 4 * mm))
el.append(P("4. Échéancier de paiement proposé", H1))
mil = [("Commande / démarrage du cadrage", 0.30), ("Intégration opérateurs : bind et flux de test validés", 0.25), ("PV de recette signé", 0.30), ("Mise en production + transfert (solde)", 0.15)]
md = [["Jalon", "%", "Montant HT (TND)"]] + [[a, f"{int(p*100)} %", tnd(total * p)] for a, p in mil] + [["Total", "100 %", tnd(total)]]
el.append(table(md, [110 * mm, 20 * mm, 50 * mm], right=(1, 2), total=True))
el.append(Spacer(1, 3 * mm))
el.append(P("Le montant total est un maximum : toute demande hors périmètre fait l'objet d'un avenant. Les dépendances externes (opérateurs, juridique, infrastructure) ne sont pas maîtrisées par le prestataire et peuvent décaler le calendrier sans modifier le prix.", NOTE))
doc.build(el, onFirstPage=footer, onLaterPages=footer)
print(f"PDF : {OUT}\nJH={jh} base={base} aleas={conting} total={total} TTC={total*(1+TVA):.0f} marge_sous_plafond={CAP-total}")

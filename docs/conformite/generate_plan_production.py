#!/usr/bin/env python3
"""Plan de passage en production (les 6 étapes restantes) : une seule source pour docs/23-plan-passage-production.md et le PDF client.
Usage : python3 generate_plan_production.py"""
import os
import re
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

HERE = os.path.dirname(os.path.abspath(__file__))
MD = os.path.join(HERE, "..", "23-plan-passage-production.md")
PDF = os.path.join(HERE, "Plan_passage_production_VAS_Tunisie.pdf")
DATE = "16/10/2026"
TITLE = "Plan de passage en production — Plateforme VAS / SMS Premium (Tunisie)"
INTRO = ("Le code couvre tout le périmètre du cahier des charges ; environ 74 % de la matrice de recette est validé en conditions réelles. "
         "Les 6 étapes ci-dessous mènent à 100 %. Aucune ne demande de développement : chacune dispose d'un outil ou d'une procédure fournis dans le dépôt, "
         "et produit un procès-verbal. Les semaines renvoient au planning de l'offre (8 semaines, lots 1 à 13).")

SUMMARY = [
    ("1", "Recette avec les 3 opérateurs", "Prestataire + opérateurs", "Sem. 2 à 5 (lots 3 à 6)", "Outil prêt, testé", "A02, A09 à A13 « OP », §14.2"),
    ("2", "Recette avec les partenaires SMPP", "Prestataire + partenaires", "Sem. 5 à 8 (lot 13)", "Outil prêt, testé (18 / 18)", "A22, A26 « PART »"),
    ("3", "Charge à 200 SMS/s et bascule HA en préproduction", "Prestataire", "Sem. 5 à 7 (lot 8)", "Outils prêts ; à exécuter sur l'infrastructure cible", "A09, CDC §12.1-12.2"),
    ("4", "Test d'intrusion et avis juridique", "Client (commande) + prestataire", "Sem. 6 à 7 (lot 9)", "Périmètre et propositions prêts", "CDC §11.3, sécurité"),
    ("5", "Contrats et comptes des tiers", "Client", "Dès J+5, au plus tard sem. 5", "Liste et variables prêtes", "A17 à A21, A25"),
    ("6", "Applications Android / iOS", "Prestataire (build) + client (stores)", "Sem. 6 à 8", "Builds réussis dans le CI", "A25"),
]

STEPS = [
 ("1. Recette avec les 3 opérateurs (Tunisie Telecom, Orange, Ooredoo)",
  [("Prérequis", "VPN et compte SMPP de l'environnement de test de l'opérateur ; short codes attribués ; une SIM de test par opérateur ; fiche `docs/operateurs/fiche-parametrage-operateur.md` remplie ; valeurs `{OP}_*` dans `.env`, `tools/check-env.py` sans erreur."),
   ("Exécution", "`infra/jasmin/provision.sh` (liaison), puis `BASE=… API_KEY=… OPERATOR=TT SIM=… SENDER=… node tests/recette/operator-check.mjs` : envoie un SMS GSM, un SMS arabe (UCS-2) et un SMS de 3 segments vers la SIM, suit chacun jusqu'à l'accusé final et écrit un procès-verbal pré-rempli."),
   ("Cas manuels du PV", "Réception sur le téléphone ; MO et réponse ; STOP (français et arabe) ; codes d'erreur des accusés (numéro éteint, inexistant) ; débit contractuel ; coupure demandée à l'opérateur ; import du relevé de facturation et rapprochement sans écart ; connexion par VPN uniquement."),
   ("Critère d'acceptation", "3 / 3 cas automatiques et 8 / 8 cas manuels OK ; PV signé par l'opérateur, le client et le prestataire."),
   ("Livrable", "Un PV par opérateur (`recette-<OP>-<date>.md`).")]),
 ("2. Recette avec les partenaires qui se connectent en SMPP",
  [("Prérequis", "Accès SMPP créé dans Canaux → Accès SMPP (system_id, adresses IP), VPN ou tunnel TLS du partenaire, short code et service actifs, numéro de test."),
   ("Exécution", "Côté plateforme : `java -Dloader.main=tn.vas.tools.SmppCheck -cp vas-platform.jar org.springframework.boot.loader.launch.PropertiesLauncher hôte port system_id mot_de_passe expéditeur numéro [numéro2] [attente_s]` : 18 contrôles de la matrice SMPP v3.4 (bind, enquire_link, submit_sm GSM / UCS-2 / UDH, submit_multi, data_sm, échéance, query_sm, replace_sm, cancel_sm, codes 0x11 / 0x67 / 0x0B / 0x62, accusé avec TLV, unbind). Côté partenaire : son propre client SMPP rejoue ses cas d'usage, contrôlés avec la matrice `22-conformite-smpp-v34.md`."),
   ("Critère d'acceptation", "18 / 18 contrôles OK depuis le réseau du partenaire ; ses cas d'usage passent ; accusés et MO reçus par son système."),
   ("Livrable", "Sortie de SmppCheck jointe au PV de chaque partenaire.")]),
 ("3. Test de charge à 200 SMS/s et haute disponibilité en préproduction",
  [("Prérequis", "Préproduction dimensionnée comme la production (offre : 7 machines virtuelles), pile HA démarrée (`docker compose -f docker-compose.yml -f infra/ha/docker-compose.ha.yml up -d`), liaison SMPP vers le simulateur ou l'environnement de test des opérateurs, jamais vers de vrais abonnés."),
   ("Charge", "`k6 run -e BASE=… -e SECRET=… -e API_KEY=… tests/load/k6-mo-mt.js` : MO en rampe jusqu'à 200/s pendant 5 min et API à 100/s ; seuils intégrés : P95 MO < 2 s, P95 API < 500 ms, moins de 0,1 % d'erreurs. Suivi des files RabbitMQ et de la base dans Grafana pendant l'essai."),
   ("Bascule", "`BASE=… API_KEY=… SIM=… infra/ha/drill.sh` : une sonde envoie un MT toutes les 200 ms pendant l'arrêt d'une instance applicative, du primaire PostgreSQL (promotion Patroni), d'un nœud RabbitMQ et du primaire Redis ; mesure de la plus longue interruption et contrôle qu'aucun MT accepté n'est perdu."),
   ("Critère d'acceptation", "Seuils k6 respectés à 200 SMS/s ; chaque bascule ≤ 30 s ; 0 MT accepté perdu ; restauration de sauvegarde vérifiée (`infra/backup/verify-restore.sh`)."),
   ("Livrable", "Rapport k6, `ha-drill-<date>.md`, rapport de restauration, intégrés à `12-rapport-tests.md`.")]),
 ("4. Test d'intrusion et avis juridique",
  [("Test d'intrusion", "Commandé par le client à un auditeur indépendant (certifié ANSI selon le statut du client) ; réservation dès J+10. Périmètre proposé : back-office et portail (web), API partenaires `/api/v1` (clés et OAuth2), callbacks opérateurs (`/callbacks/*`), webhooks WhatsApp / RCS, serveur SMPP partenaires, applications mobiles, configuration TLS et en-têtes. Référentiel : OWASP ASVS niveau 2 et OWASP Top 10. Comptes fournis : un compte par rôle (7 rôles), un client API, un accès SMPP de test. Hors périmètre : réseaux des opérateurs, déni de service."),
   ("Corrections", "Chaque constat est corrigé et retesté par le prestataire ; le rapport de l'auditeur et le suivi des corrections complètent `07-rapport-securite.md`."),
   ("Avis juridique", "Le conseil juridique du client fixe les durées de conservation, aujourd'hui sans purge (`RETENTION_*_DAYS=0`), et valide les textes d'information des abonnés (FR / AR) et les mentions des services. Valeurs de départ à lui soumettre, sans valeur légale : messages et MO 12 mois ; preuves de consentement durée de l'abonnement + 3 ans ; journal d'audit 3 ans ; journaux de webhooks 90 jours ; pièces de facturation selon la durée légale de conservation des pièces comptables. Points à trancher : déclaration ou autorisation de traitement auprès de l'INPDP, transfert de données vers Meta (WhatsApp) et le fournisseur RCS."),
   ("Critère d'acceptation", "Aucun constat critique ou élevé ouvert ; avis juridique écrit ; valeurs reportées dans `.env` (`RETENTION_*`) et textes chargés dans les services."),
   ("Livrable", "Rapport d'audit et attestation de correction ; avis juridique ; `.env` mis à jour.")]),
 ("5. Contrats et comptes des tiers",
  [("Opérateurs", "Comptes SMPP de test puis de production, VPN, short codes ; codes USSD et accès à leur passerelle (`/callbacks/ussd`) ; contrat et API de paiement sur facture (`{OP}_DCB_URL`, `{OP}_DCB_TOKEN`) ; numéros IVR et trunk SIP pour Asterisk."),
   ("Meta WhatsApp Business", "Compte Business Manager vérifié, numéro dédié, application et jeton permanent, secret d'application pour la signature des webhooks, modèles soumis à l'approbation de Meta (`WHATSAPP_*`)."),
   ("RCS", "Contrat avec un fournisseur RCS couvrant les opérateurs tunisiens, agent vérifié, URL et secret de webhook (`RCS_*`)."),
   ("Google Play et Apple", "Comptes développeur au nom du client : Google Play (25 USD, une fois), Apple Developer Program (99 USD par an ; numéro D-U-N-S pour une organisation), certificats et profils de signature."),
   ("Critère d'acceptation", "Chaque compte ouvert, ses valeurs saisies dans `.env`, `tools/check-env.py` sans erreur ; cas A17 à A21 rejoués avec le tiers réel."),
   ("Livrable", "Tableau des comptes (titulaire, date, échéance de renouvellement) remis au client.")]),
 ("6. Applications Android / iOS",
  [("Fait", "Les jobs `mobile-android` (APK de test publié comme artefact) et `mobile-ios` (compilation pour simulateur) du CI réussissent sur la branche livrée (exécution du 16/10/2026)."),
   ("Reste", "Signature avec les certificats du client, fiches des stores (captures, description FR / AR, politique de confidentialité), test interne (Play Console, TestFlight), soumission. Apple peut refuser une application qui ne fait qu'afficher un site (règle 4.2) : bien décrire les fonctions propres à l'application à la soumission."),
   ("Critère d'acceptation", "Applications acceptées par Google et Apple et installables depuis les stores."),
   ("Livrable", "Liens des fiches publiées.")]),
]


def md():
    out = [f"# {TITLE}", "", INTRO, "", "## Synthèse", "",
           "| # | Étape | Responsable | Quand | État de préparation | Couvre |", "|---|---|---|---|---|---|"]
    out += ["| " + " | ".join(r) + " |" for r in SUMMARY]
    for title, items in STEPS:
        out += ["", f"## {title}", ""]
        out += [f"- **{k}** : {v}" for k, v in items]
    out += ["", "PDF client : `docs/conformite/Plan_passage_production_VAS_Tunisie.pdf` (généré par `docs/conformite/generate_plan_production.py`).", ""]
    with open(MD, "w", encoding="utf-8") as f:
        f.write("\n".join(out))


def pdf():
    for n, f in [("DV", "DejaVuSans"), ("DVB", "DejaVuSans-Bold"), ("DVM", "DejaVuSansMono")]:
        pdfmetrics.registerFont(TTFont(n, f"/usr/share/fonts/truetype/dejavu/{f}.ttf"))
    ss = getSampleStyleSheet()
    B = ParagraphStyle("B", parent=ss["Normal"], fontName="DV", fontSize=8.8, leading=12)
    S = ParagraphStyle("S", parent=B, fontSize=7.6, leading=10)
    SB = ParagraphStyle("SB", parent=S, fontName="DVB", textColor=colors.white)
    K = ParagraphStyle("K", parent=S, fontName="DVB")
    H1 = ParagraphStyle("H1", parent=B, fontName="DVB", fontSize=15, leading=19, spaceAfter=6, textColor=colors.HexColor("#0b3d91"))
    H2 = ParagraphStyle("H2", parent=B, fontName="DVB", fontSize=10.5, leading=14, spaceBefore=9, spaceAfter=4, keepWithNext=1, textColor=colors.HexColor("#0b3d91"))

    def fmt(t):
        t = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        t = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", t)
        return re.sub(r"`(.+?)`", r"<font face='DVM' size='7'>\1</font>", t)

    width = A4[0] - 30 * mm
    grid = [("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#b0b8c8")), ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 3), ("RIGHTPADDING", (0, 0), (-1, -1), 3)]
    head = ["#", "Étape", "Responsable", "Quand", "Préparation", "Couvre"]
    t = Table([[Paragraph(h, SB) for h in head]] + [[Paragraph(fmt(c), S) for c in r] for r in SUMMARY],
              colWidths=[w * width for w in (0.04, 0.25, 0.17, 0.16, 0.2, 0.18)], repeatRows=1)
    t.setStyle(TableStyle(grid + [("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0b3d91"))]))
    story = [Paragraph(fmt(TITLE), H1), Paragraph(f"Version du {DATE}", S), Spacer(1, 4), Paragraph(fmt(INTRO), B), Paragraph("Synthèse", H2), t]
    for title, items in STEPS:
        st = Table([[Paragraph(fmt(k), K), Paragraph(fmt(v), S)] for k, v in items], colWidths=[0.2 * width, 0.8 * width])
        st.setStyle(TableStyle(grid + [("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#eef2f9"))]))
        story += [Paragraph(fmt(title), H2), st]

    def footer(cv, doc):
        cv.setFont("DV", 7)
        cv.setFillColor(colors.HexColor("#666666"))
        cv.drawString(15 * mm, 8 * mm, f"Plan de passage en production — plateforme VAS / SMS Premium — {DATE}")
        cv.drawRightString(A4[0] - 15 * mm, 8 * mm, f"Page {doc.page}")

    SimpleDocTemplate(PDF, pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm, topMargin=14 * mm, bottomMargin=15 * mm,
                      title="Plan de passage en production", author="Plateforme VAS").build(story, onFirstPage=footer, onLaterPages=footer)


if __name__ == "__main__":
    md()
    pdf()
    print("écrit :", os.path.normpath(MD), os.path.normpath(PDF))

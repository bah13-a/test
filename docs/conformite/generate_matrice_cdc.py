#!/usr/bin/env python3
"""Matrice de conformité ligne par ligne au cahier des charges V1.0 du 05/10/2026 (§18, point 3).
Une seule source pour docs/24-matrice-conformite-cdc.md et le PDF client. Usage : python3 generate_matrice_cdc.py
Statuts : C = conforme, P = partiel (écart décrit), N = non couvert. Priorité : M = MUST, S = SHOULD."""
import os
import re
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

HERE = os.path.dirname(os.path.abspath(__file__))
MD = os.path.join(HERE, "..", "24-matrice-conformite-cdc.md")
PDF = os.path.join(HERE, "Matrice_conformite_CDC_VAS_Tunisie.pdf")
DATE = "16/10/2026"

# (section, [(id, priorité, exigence, statut, écart)])
ROWS = [
 ("§1-2 Contraintes et choix de gateway", [
  ("1.2-a", "M", "Paramètres opérateurs non codés en dur", "P", "Délais de reconnexion et de remise en file figés dans le gabarit Jasmin ; address_range et timeouts absents ; liste des 3 opérateurs figée"),
  ("2.3-a", "M", "Client SMPP obligatoire ; serveur SMPP souhaité", "C", ""),
  ("2.3-b", "M", "Routage MO/MT par opérateur, short code, destination, service, règles", "P", "Pas de règles de routage métier ; côté Jasmin, routes par utilisateur seulement"),
  ("2.3-c", "M", "Auto-reconnect, store-and-forward, retry contrôlé, files persistantes", "P", "Retry à délai fixe ; un MT passe en échec si tous les liens tombent plus de 3 à 4 min"),
  ("2.3-d", "M", "Configuration automatisable, sauvegardable, versionnée", "P", "Ni .env ni configuration Jasmin dans la sauvegarde ; reprovisionnement non idempotent"),
 ]),
 ("§5 SMPP v3.4", [
  ("SMPP-001", "M", "SMPP v3.4 conforme aux paramètres contractuels", "P", "Dépend de SMPP-004 ; recette opérateur à faire"),
  ("SMPP-002", "M", "Bind TRX / TX / RX configurables", "P", "Un seul mode par opérateur : couple TX + RX séparé impossible"),
  ("SMPP-003", "M", "submit_sm, deliver_sm, enquire_link, unbind, generic_nack", "C", ""),
  ("SMPP-004", "M", "Host, port, system_id, password, system_type, address_range, TON/NPI, bind, window, TPS, timeout", "P", "address_range, timeouts, bind TON/NPI absents ; window non paramétrable dans Jasmin"),
  ("SMPP-005", "M", "Auto-reconnexion avec backoff et anti-boucle", "P", "Délai fixe 10 s, sans backoff ni coupe-circuit"),
  ("SMPP-006", "M", "enquire_link configurable, détection de session dégradée", "P", "Aucune métrique ni alerte sur l'état des liaisons"),
  ("SMPP-007", "M", "Throttling opérateur, adaptation du débit sans perte", "P", "Limiteur par instance (débit doublé en HA) ; pas d'adaptation dynamique"),
  ("SMPP-008", "S", "Connexions multiples et liens de secours", "P", "Secours avec les mêmes identifiants et port uniquement"),
  ("SMPP-009", "M", "Journal des événements SMPP, codes d'erreur, états de connexion", "P", "Changements d'état non journalisés ni collectés"),
  ("SMPP-010", "M", "Secrets masqués dans logs et interfaces", "P", "Le script de provisionnement affiche les mots de passe ; secret de callback dans les URL"),
  ("5.2-a", "M", "TON/NPI par connecteur et par route", "P", "Par connecteur seulement"),
  ("5.2-b", "M", "Sender numérique, alphanumérique, short code", "P", "Pas de contrôle des senders autorisés ni de TON adapté à l'alphanumérique"),
  ("5.2-c", "M", "GSM 7 bits, 8 bits / binaire, UCS-2", "P", "Envoi 8 bits / binaire impossible"),
  ("5.2-d", "M", "SMS longs UDH et/ou SAR configurable", "P", "Choix UDH / SAR non exposé vers les opérateurs"),
  ("5.2-e", "M", "Nombre de segments exposé au moteur de coût", "P", "Segments calculés mais non facturés (quantité 1)"),
  ("5.2-f", "M", "Normalisation MSISDN par opérateur et sens", "N", "Normalisation +216 codée en dur"),
  ("5.3", "M", "Fiche de paramétrage : intégrer un opérateur sans code", "P", "Environnement, contacts NOC, format DLR non paramétrables ; 4e opérateur = modification du code"),
 ]),
 ("§3 Cas d'usage", [
  ("UC-01", "M", "Vote SMS", "C", ""),
  ("UC-02", "M", "Quiz / jeu", "C", ""),
  ("UC-03", "M", "Contenu premium", "P", "Par API : réglementaire, antifraude et plafonds non contrôlés"),
  ("UC-04", "M", "Abonnement récurrent", "P", "Renouvellement global, pas par opérateur ; STOP par API sans trace de consentement"),
  ("UC-05", "M", "Campagne partenaire", "C", ""),
  ("UC-06", "M", "API partenaire jusqu'au callback", "C", ""),
  ("UC-07", "M", "Failover opérateur / lien", "P", "Pas d'alerte sur la liaison ; file non conservée au-delà de 3 à 4 min"),
  ("3-conf", "M", "Activités réglementées activables / bloquables", "P", "Pas de catégories ; contrôle absent sur API et SMPP ; un gestionnaire peut lever le blocage"),
 ]),
 ("§6 Flux MO, MT, DLR", [
  ("MO-001", "M", "Réception deliver_sm, identifiant unique", "C", ""),
  ("MO-002", "M", "Champs MO (dont encodage et message_id)", "P", "Encodage non conservé"),
  ("MO-003", "M", "Doublons, fenêtre configurable", "C", ""),
  ("MO-004", "M", "Routage opérateur + short code + keyword + période + règles", "P", "Même mot-clé sur deux campagnes d'un short code : choix arbitraire ; validité du short code non contrôlée"),
  ("MO-005", "M", "Keyword inconnu : réponse paramétrable ou rejet", "P", "Rejet silencieux seulement"),
  ("MO-006", "S", "Webhook partenaire après validation", "C", ""),
  ("MO-007", "M", "Accusé applicatif et retry", "C", ""),
  ("MT-001", "M", "Corrélation ID interne / message_id SMSC", "P", "Identifiant Jasmin conservé, pas celui du SMSC"),
  ("MT-002", "M", "Priorités configurables", "P", "Priorités des MT du moteur codées en dur"),
  ("MT-003", "M", "Queue persistante, non-perte", "C", ""),
  ("MT-004", "M", "Retry configurable par code erreur", "P", "Pas de table code d'erreur → retry / échec"),
  ("MT-005", "M", "Validité configurable", "P", "24 h codées en dur (sauf serveur SMPP)"),
  ("MT-006", "S", "Envoi programmé", "C", ""),
  ("MT-007", "M", "Débit par opérateur, compte, service, partenaire", "P", "Compte : quota de requêtes et non débit d'envoi ; limiteur par instance"),
  ("DLR-a", "M", "Réception et interprétation des DLR", "C", ""),
  ("DLR-b", "M", "Statuts normalisés (dont ACCEPTED)", "P", "ACCEPTED absent"),
  ("DLR-c", "M", "Statut brut conservé", "C", ""),
  ("DLR-d", "M", "Horodatage de chaque transition", "C", ""),
  ("DLR-e", "M", "Callback DLR signé", "C", ""),
  ("DLR-f", "M", "Retry des callbacks avec DLQ", "P", "Pas d'écran ni d'API pour consulter / rejouer la DLQ"),
  ("DLR-g", "M", "Règle de facturation sur DLR par service et opérateur", "P", "Par opérateur seulement"),
 ]),
 ("§7 Moteur VAS", [
  ("7.1-op", "M", "Opérateur : contacts, environnement, connecteurs…", "P", "Contacts et environnement absents"),
  ("7.1-sc", "M", "Short code : type, validité, TPS, statut", "P", "Type et TPS absents ; validité non appliquée"),
  ("7.1-svc", "M", "Service : description, catégorie, dates, SLA…", "P", "Description, catégorie, SLA absents"),
  ("7.1-kw", "M", "Keyword : variantes, casse, réponse par défaut", "P", "Variantes, casse et réponse par défaut absentes"),
  ("7.1-tar", "M", "Tarif : opérateur, short code, unité, frais, dates", "P", "Unité, frais, date de fin absents"),
  ("7.1-scn", "M", "Scénario configurable", "N", "Scénarios codés par type de service"),
  ("7.1-sub", "M", "Abonnement complet", "P", "Source du consentement et périodicité non portées"),
  ("7.2-a", "M", "Activation / désactivation immédiate", "C", ""),
  ("7.2-b", "M", "Périodes de campagne avec timezone", "P", "Pas de fuseau horaire configurable"),
  ("7.2-c", "M", "Réponses par opérateur, langue, résultat, statut", "P", "Par langue seulement"),
  ("7.2-d", "M", "FR / AR / EN contenus et interfaces", "P", "Questions, options et contenus monolingues"),
  ("7.2-e", "M", "STOP / DESABO / AIDE configurables par service", "P", "Mots globaux, seuls les textes sont configurables"),
  ("7.2-f", "M", "Actions max par MSISDN, campagne, période", "P", "Pas de période"),
  ("7.2-g", "M", "Blacklist / whitelist", "C", ""),
  ("7.2-h", "M", "Traçabilité des modifications de tarifs et règles", "P", "Valeurs avant modification non journalisées"),
  ("7.3-a", "M", "Schémas de consentement par service", "P", "Pas de mode web ni de confirmation SMS distincte"),
  ("7.3-b", "M", "Preuve : source, canal, version des conditions", "P", "Version des conditions figée à v1"),
  ("7.3-c", "M", "Confirmation post-activation configurée", "P", "Activation API ignore les textes par langue"),
  ("7.3-d", "M", "Désinscription immédiate et horodatée", "P", "STOP par API / support sans preuve ni SMS"),
  ("7.3-e", "M", "Blocage des renouvellements après STOP", "C", ""),
  ("7.3-f", "M", "Export d'historique des consentements", "P", "Par numéro et service seulement, CSV seulement"),
 ]),
 ("§8 Billing et rapprochement", [
  ("8.1", "M", "Ledger immuable / auditable, champs requis", "P", "Keyword, frais, dates de facturation / rapprochement / correction absents ; pas d'immuabilité en base"),
  ("8.2-a", "M", "Tarifs versionnés, pas de rétroactivité", "C", ""),
  ("8.2-b", "M", "Tarif par opérateur, short code, service, type", "P", "Par service et type seulement"),
  ("8.2-c", "M", "Prix facial, partage, règles configurables", "P", "Part partenaire hors tarif, formule fixe"),
  ("8.2-d", "M", "Simulation avant activation", "P", "Simulation libre, pas d'un tarif brouillon"),
  ("8.2-e", "M", "Approbation des changements tarifaires", "C", ""),
  ("8.3-a", "M", "Import CSV / XLSX ou API", "P", "Pas d'import par API"),
  ("8.3-b", "M", "Mapping configurable", "C", ""),
  ("8.3-c", "M", "Rapprochement par id, période, service, volumes, montants", "P", "Pas de contrôle de l'opérateur ; ni service ni volumes"),
  ("8.3-d", "M", "Écarts : volume, statut, montant, duplicata, absence", "P", "Volume absent ; statut, duplicata, absence partiels"),
  ("8.3-e", "M", "Dashboard de réconciliation et export", "P", "Pas de liste des lots ni de totaux"),
  ("8.3-f", "M", "Journal de correction et commentaire", "P", "Commentaire écrasé ; correction hors automate de statuts"),
 ]),
 ("§9 API", [
  ("9.1", "M", "Endpoints logiques (dont /webhooks/dlr, /health restreint)", "P", "Modèle de webhook DLR non documenté dans l'OpenAPI ; /health public"),
  ("9.2-a", "M", "HTTPS obligatoire", "P", "Callbacks internes en HTTP"),
  ("9.2-b", "M", "OAuth2 / API key, pas de mot de passe en clair", "C", ""),
  ("9.2-c", "M", "Scopes et quotas par client", "P", "Pas de quota de volume journalier / mensuel"),
  ("9.2-d", "M", "Rate limiting", "C", ""),
  ("9.2-e", "M", "Signature HMAC des webhooks", "P", "Secret partenaire facultatif ; callbacks Jasmin sans HMAC"),
  ("9.2-f", "M", "Protection anti-rejeu", "P", "Callbacks entrants sans horodatage ni nonce"),
  ("9.2-g", "M", "Journal des accès API", "C", ""),
  ("9.2-h", "M", "OpenAPI livrée", "P", "Sans schémas de sécurité ni codes d'erreur"),
 ]),
 ("§10 Back-office et portail", [
  ("10.1-a", "M", "Dashboard : binds, TPS, files, erreurs, dispo", "P", "État réel des binds, TPS réel, erreurs SMPP absents"),
  ("10.1-b", "M", "Opérateurs / connecteurs sans exposition des secrets", "P", "Connecteurs non gérés dans le back-office"),
  ("10.1-c", "M", "Clients, services, short codes, keywords, tarifs, campagnes", "C", ""),
  ("10.1-d", "M", "Recherche de message", "P", "MT seulement, pas les MO"),
  ("10.1-e", "M", "Console de réconciliation", "C", ""),
  ("10.1-f", "M", "Exports CSV / XLSX / PDF", "C", ""),
  ("10.1-g", "M", "Utilisateurs, rôles, MFA, audit", "C", ""),
  ("10.2", "M", "7 rôles minimum", "C", ""),
  ("10.3", "S", "Dashboard partenaire", "P", "Pas de choix de période ; DLR non distingués"),
 ]),
 ("§11 Sécurité et conformité", [
  ("11.1-a", "M", "Segmentation réseau", "P", "Un seul réseau Docker"),
  ("11.1-b", "M", "Firewall, allowlist, pas d'admin public", "P", "Allowlist admin vide par défaut ; Swagger public"),
  ("11.1-c", "M", "VPN / IPsec opérateurs", "N", "Aucune configuration ni procédure"),
  ("11.1-d", "M", "SSH par clés / bastion", "P", "Mentionné, sans procédure"),
  ("11.1-e", "M", "Durcissement OS et patchs documentés", "P", "Pas de guide de durcissement"),
  ("11.1-f", "M", "Coffre de secrets, rotation", "P", "Pas de coffre ; rotation manuelle"),
  ("11.1-g", "M", "Sauvegardes chiffrées et restauration testée", "P", "Pas d'archivage WAL ; test sur petit volume"),
  ("11.2-a", "M", "RBAC, MFA, mots de passe, sessions", "C", ""),
  ("11.2-b", "M", "Protection OWASP", "P", "Pas de DAST ni de test d'intrusion"),
  ("11.2-c", "M", "TLS web / API", "C", ""),
  ("11.2-d", "M", "Chiffrement au repos des données sensibles", "P", "Secrets TOTP et webhook en clair"),
  ("11.2-e", "M", "Masquage MSISDN selon rôle", "C", ""),
  ("11.2-f", "M", "Aucun secret dans Git", "C", ""),
  ("11.2-g", "M", "Scan de dépendances bloquant", "P", "Scans non bloquants en CI"),
  ("11.3-a", "M", "Journal d'audit non modifiable et complet", "P", "Échecs de connexion et plusieurs exports non audités"),
  ("11.3-b", "M", "Versionnage des paramètres critiques", "P", "Tarifs seulement"),
  ("11.3-c", "M", "Preuve de consentement et historique d'un service", "P", "Historique d'un service non extractible"),
  ("11.3-d", "M", "Suspension immédiate service / short code", "C", ""),
  ("11.3-e", "M", "Rapport de sécurité et d'architecture", "P", "Incohérences à corriger ; intrusion non faite"),
 ]),
 ("§12 Performance, HA, supervision", [
  ("12.1", "M", "50 SMS/s par opérateur, 200 agrégés, P95, RPO, RTO", "P", "200 SMS/s non mesuré ; RPO 15 min non tenu sans WAL"),
  ("12.2", "M", "Pas de SPOF, bascule testée, PRA", "P", "etcd, répartiteurs et Jasmin non redondés ; bascule non exécutée"),
  ("12.3", "M", "Monitoring binds, TPS, erreurs SMPP, files, DLQ, infra, alertes", "P", "Seule l'application est supervisée ; pas de métriques SMPP ni d'infrastructure"),
 ]),
 ("§13-16 Exploitation, tests, livrables, maintenance", [
  ("13.1", "M", "DEV, SIT, PREPROD, PROD", "P", "SIT et PREPROD non définis"),
  ("13.2", "M", "Tags, pipelines, rollback, release notes", "P", "Pas de tags, de déploiement, de rollback ni de notes de version"),
  ("13.3", "M", "Simulateur SMPP complet", "P", "Pas de timeouts ni d'erreurs submit_sm simulés"),
  ("14.1", "M", "Tests unitaires, intégration, charge, panne, sécurité, UAT", "P", "Pannes base / broker / Jasmin et callback en erreur non testés"),
  ("15.1", "M", "Livrables obligatoires", "P", "Schéma réseau détaillé, supports de formation, rapports charge / intrusion"),
  ("16", "M", "Rapport mensuel de maintenance", "N", "Aucun modèle ni outil"),
 ]),
]


def stats():
    rows = [r for _, rs in ROWS for r in rs]
    n = len(rows)
    c = sum(r[3] == "C" for r in rows)
    p = sum(r[3] == "P" for r in rows)
    k = sum(r[3] == "N" for r in rows)
    return n, c, p, k, round(100 * (c + 0.5 * p) / n)


LABEL = {"C": "Conforme", "P": "Partiel", "N": "Non couvert"}


def md():
    n, c, p, k, w = stats()
    out = ["# Matrice de conformité au cahier des charges V1.0 (05/10/2026)", "",
           f"Réponse au §18, point 3. Version du {DATE}. Chaque ligne a été vérifiée dans le code. "
           f"**{n} exigences : {c} conformes, {p} partielles, {k} non couvertes ; couverture pondérée {w} %** (partiel = ½).", "",
           "Les options du §2.2 (USSD, DCB, IVR, WhatsApp / RCS, BI, antifraude, application mobile, serveur SMPP) sont développées et ne sont pas comptées ici.", ""]
    for sec, rs in ROWS:
        out += [f"## {sec}", "", "| ID | Prio | Exigence | Statut | Écart |", "|---|---|---|---|---|"]
        out += [f"| {i} | {'MUST' if pr == 'M' else 'SHOULD'} | {e} | {LABEL[s]} | {g or '-'} |" for i, pr, e, s, g in rs]
        out.append("")
    with open(MD, "w", encoding="utf-8") as f:
        f.write("\n".join(out))


def pdf():
    for a, f in [("DV", "DejaVuSans"), ("DVB", "DejaVuSans-Bold")]:
        pdfmetrics.registerFont(TTFont(a, f"/usr/share/fonts/truetype/dejavu/{f}.ttf"))
    ss = getSampleStyleSheet()
    B = ParagraphStyle("B", parent=ss["Normal"], fontName="DV", fontSize=8.6, leading=11.5)
    S = ParagraphStyle("S", parent=B, fontSize=7.3, leading=9.3)
    SB = ParagraphStyle("SB", parent=S, fontName="DVB", textColor=colors.white)
    H1 = ParagraphStyle("H1", parent=B, fontName="DVB", fontSize=15, leading=19, spaceAfter=6, textColor=colors.HexColor("#0b3d91"))
    H2 = ParagraphStyle("H2", parent=B, fontName="DVB", fontSize=10, leading=13, spaceBefore=7, spaceAfter=3, keepWithNext=1, textColor=colors.HexColor("#0b3d91"))
    page = landscape(A4)
    width = page[0] - 24 * mm
    bg = {"C": "#e6f4ea", "P": "#fff6e0", "N": "#fde7e7"}
    n, c, p, k, w = stats()
    esc = lambda t: re.sub(r"[&<>]", lambda m: {"&": "&amp;", "<": "&lt;", ">": "&gt;"}[m.group()], t)
    story = [Paragraph("Matrice de conformité au cahier des charges V1.0", H1),
             Paragraph(f"Réponse au §18, point 3 — version du {DATE}", S), Spacer(1, 4),
             Paragraph(f"Chaque exigence a été vérifiée dans le code. <b>{n} exigences : {c} conformes, {p} partielles, {k} non couvertes ; "
                       f"couverture pondérée {w} %</b> (une exigence partielle compte pour moitié). Les options du §2.2 sont développées et ne sont pas comptées ici.", B)]
    for sec, rs in ROWS:
        data = [[Paragraph(h, SB) for h in ("ID", "Prio", "Exigence", "Statut", "Écart")]]
        data += [[Paragraph(i, S), Paragraph("MUST" if pr == "M" else "SHOULD", S), Paragraph(esc(e), S), Paragraph(LABEL[s], S), Paragraph(esc(g or "-"), S)] for i, pr, e, s, g in rs]
        t = Table(data, colWidths=[x * width for x in (0.08, 0.06, 0.34, 0.08, 0.44)], repeatRows=1)
        st = [("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0b3d91")), ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#b0b8c8")),
              ("VALIGN", (0, 0), (-1, -1), "TOP"), ("LEFTPADDING", (0, 0), (-1, -1), 3), ("RIGHTPADDING", (0, 0), (-1, -1), 3)]
        st += [("BACKGROUND", (3, j), (3, j), colors.HexColor(bg[r[3]])) for j, r in enumerate(rs, 1)]
        t.setStyle(TableStyle(st))
        story += [Paragraph(esc(sec), H2), t]

    def footer(cv, doc):
        cv.setFont("DV", 7)
        cv.setFillColor(colors.HexColor("#666666"))
        cv.drawString(12 * mm, 8 * mm, f"Matrice de conformité CDC V1.0 — plateforme VAS / SMS Premium — {DATE}")
        cv.drawRightString(page[0] - 12 * mm, 8 * mm, f"Page {doc.page}")

    SimpleDocTemplate(PDF, pagesize=page, leftMargin=12 * mm, rightMargin=12 * mm, topMargin=12 * mm, bottomMargin=14 * mm,
                      title="Matrice de conformité CDC", author="Plateforme VAS").build(story, onFirstPage=footer, onLaterPages=footer)


if __name__ == "__main__":
    md()
    pdf()
    print("exigences / conformes / partielles / non couvertes / pondéré % :", *stats())

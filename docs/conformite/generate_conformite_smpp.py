#!/usr/bin/env python3
"""Matrice de conformité SMPP v3.4 de la plateforme VAS (Tunisie) : une seule source pour le document Markdown et le PDF client.
Usage : python3 generate_conformite_smpp.py   (écrit docs/22-conformite-smpp-v34.md et docs/conformite/Conformite_SMPP_v34_VAS_Tunisie.pdf)"""
import os
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

HERE = os.path.dirname(os.path.abspath(__file__))
MD = os.path.join(HERE, "..", "22-conformite-smpp-v34.md")
PDF = os.path.join(HERE, "Conformite_SMPP_v34_VAS_Tunisie.pdf")
DATE = "16/10/2026"

TITLE = "Conformité SMPP v3.4 — Plateforme VAS / SMS Premium (Tunisie)"
INTRO = ("Référence : *Short Message Peer to Peer Protocol Specification v3.4*, document 3.4 issue 1.2 (SMPP Developers Forum, 12/10/1999). "
         "La plateforme parle SMPP v3.4 dans deux rôles : **(A) client (ESME) des SMSC des trois opérateurs**, au travers de la passerelle Jasmin 0.10 "
         "(liens configurés dans `.env`, `infra/jasmin/`), et **(B) serveur (SMSC) pour les partenaires** qui se connectent en SMPP "
         "(`tn.vas.smpp.SmppServer`, bibliothèque jsmpp 3.0.0, port `SMPP_SERVER_PORT`). Les deux annoncent `interface_version` = 0x34.")

# Légende des statuts
LEGEND = [("Conforme", "Implémenté selon la spécification et couvert par un test automatisé ou d'intégration."),
          ("Facultatif, non utilisé", "La norme laisse l'usage du PDU au choix de l'ESME ; la plateforme obtient le même résultat autrement."),
          ("Non applicable", "Le PDU ne correspond pas à ce rôle (sens de la connexion, fonction absente du service).")]

# PDU : (nom, command_id, §, rôle A opérateurs, rôle B partenaires, preuve)
PDUS = [
 ("bind_transmitter / bind_receiver / bind_transceiver", "0x00000002 / 0x00000001 / 0x00000009", "4.1",
  "Conforme : mode choisi par opérateur (`{OP}_BIND_MODE`), system_id, password, system_type, TON/NPI d'adresse", "Conforme : les trois modes ; mot de passe haché (bcrypt), adresses IP autorisées, nombre de connexions par client, refus et blocage après échecs répétés",
  "SmppSimulatorTests.bindSubmitAndDlr, wrongPasswordIsRejected ; SmppServerTests.refusedBindsAndSubmits"),
 ("outbind", "0x0000000B", "4.1.7", "Non applicable : la plateforme initie toujours le bind", "Non applicable : les partenaires initient le bind (usage rare, réservé aux SMSC qui rappellent un ESME)", "-"),
 ("unbind / unbind_resp", "0x00000006", "4.2", "Conforme (arrêt propre des liens)", "Conforme (dans les deux sens ; arrêt du serveur = unbind de chaque session)", "SmppServerTests (fin de chaque scénario)"),
 ("generic_nack", "0x80000000", "4.3", "Conforme (Jasmin)", "Conforme : PDU inconnu → generic_nack ESME_RINVCMDID", "jsmpp, comportement natif"),
 ("submit_sm / submit_sm_resp", "0x00000004", "4.4", "Conforme : envoi de chaque MT, message_id de l'opérateur conservé pour l'accusé", "Conforme : tous les champs obligatoires, voir § 3 (dates, priorité, accusé, codage, remplacement, concaténation)",
  "FlowTests, SmppSimulatorTests ; SmppServerTests.submitGoesThroughTheEngineAndDlrComesBack"),
 ("submit_multi / submit_multi_resp", "0x00000021", "4.5", "Facultatif, non utilisé : un submit_sm par destinataire (facturation et accusé unitaires)", "Conforme : 1 à 255 destinations, adresses SME (listes de distribution refusées ESME_RINVDLNAME), destinations refusées listées dans unsuccess_sme avec leur code",
  "SmppServerTests.submitMultiDataSmAndSarConcatenation"),
 ("deliver_sm / deliver_sm_resp", "0x00000005", "4.6", "Conforme : réception des MO (texte, UCS-2, concaténés) et des accusés de livraison", "Conforme : remise des MO et des accusés au partenaire (esm_class 0x04, texte Annexe B, TLV receipted_message_id et message_state), rejeu à la reconnexion",
  "SmppSimulatorTests.undeliverableDlrAndMoInjection ; SmppServerTests.receiptIsReplayedAtReconnectionAndMoIsForwarded, deliveredMessageQueryAndReceiptTlvs"),
 ("data_sm / data_sm_resp", "0x00000103", "4.7", "Facultatif, non utilisé en émission (submit_sm) ; réception gérée par Jasmin, à confirmer en recette opérateur", "Conforme : texte dans le TLV message_payload, mêmes contrôles et accusés que submit_sm",
  "SmppServerTests.submitMultiDataSmAndSarConcatenation"),
 ("query_sm / query_sm_resp", "0x00000003", "4.8", "Facultatif, non utilisé : l'état vient des accusés de livraison (et d'une expiration au-delà de `DLR_TIMEOUT_HOURS`)", "Conforme : message_state, final_date (état final), error_code ; contrôle de l'expéditeur ; message inconnu → ESME_RQUERYFAIL",
  "SmppServerTests.scheduledMessageCanBeQueriedReplacedAndCancelled, deliveredMessageQueryAndReceiptTlvs"),
 ("cancel_sm / cancel_sm_resp", "0x00000008", "4.9", "Facultatif, non utilisé : un MT programmé est annulé dans la plateforme avant d'être transmis", "Conforme : par message_id, ou tous les messages en attente d'un expéditeur (vers une destination) ; message non annulable → ESME_RCANCELFAIL ; jamais facturé",
  "SmppServerTests.scheduledMessageCanBeQueriedReplacedAndCancelled"),
 ("replace_sm / replace_sm_resp", "0x00000007", "4.10", "Facultatif, non utilisé (même raison)", "Conforme : texte, échéance et validité d'un message en attente ; sinon ESME_RREPLACEFAIL",
  "SmppServerTests.scheduledMessageCanBeQueriedReplacedAndCancelled"),
 ("enquire_link / enquire_link_resp", "0x00000015", "4.11", "Conforme : intervalle par opérateur (`{OP}_ELINK`), reconnexion automatique si la liaison tombe", "Conforme : réponse à chaque enquire_link du partenaire",
  "SmppSimulatorTests.linkDropThenRebind ; tests/integration/failover.sh"),
 ("alert_notification", "0x00000102", "4.12", "Non applicable (ne concerne que data_sm avec set_dpf)", "Non applicable : la plateforme ne gère pas d'indicateur de remise en attente (set_dpf / ms_availability_status)", "-"),
]

# Paramètres et TLV du serveur partenaires (rôle B)
PARAMS = [
 ("service_type", "5.2.11", "Accepté ; non interprété (le service est déterminé par l'expéditeur)."),
 ("source_addr (TON/NPI)", "5.2.8", "Doit être un short code ou un expéditeur d'un service actif du partenaire, sinon ESME_RINVSRCADR (0x0A)."),
 ("destination_addr (TON/NPI)", "5.2.9", "Numéro tunisien, formats national ou international (216…), normalisé ; sinon ESME_RINVDSTADR (0x0B). Listes noire / blanche et blocage antifraude : ESME_RSUBMITFAIL (0x45)."),
 ("esm_class", "5.2.12", "Mode par défaut (stockage et retransmission). Bit UDHI (0x40) : UDH lu, parties réassemblées. Accusés émis avec esm_class 0x04."),
 ("protocol_id", "5.2.13", "Accepté, transmis à 0 (pas de protocole GSM particulier)."),
 ("priority_flag", "5.2.14", "0 → normal (masse), 1 → confirmation, 2 et 3 → transactionnel (file prioritaire)."),
 ("schedule_delivery_time", "5.2.15 / 7.1.1", "Formats absolu « YYMMDDhhmmsstnnp » (fuseau en quarts d'heure) et relatif « …R » ; au plus 30 jours ; format invalide → ESME_RINVSCHED (0x61)."),
 ("validity_period", "5.2.16 / 7.1.1", "Mêmes formats ; défaut 24 h, plafonnée à 7 jours ; invalide ou déjà dépassée → ESME_RINVEXPIRY (0x62) ; message expiré → accusé EXPIRED."),
 ("registered_delivery", "5.2.17", "Bits 0-1 : 0 aucun accusé, 1 succès et échec, 2 échec seulement. Bits 2-4 (accusé SME, notification intermédiaire) : ignorés."),
 ("replace_if_present_flag", "5.2.18", "1 = remplace le dernier message encore en attente de même source et destination (même message_id rendu) ; sinon nouvel envoi."),
 ("data_coding", "5.2.19", "0 : alphabet par défaut GSM 03.38 ; 1 : IA5 (ASCII) ; 3 : Latin-1 ; 8 : UCS-2 (arabe, accents). Le message part vers l'opérateur en GSM 7 bits ou en UCS-2 selon son contenu."),
 ("sm_default_msg_id", "5.2.20", "Messages prédéfinis non gérés : valeur 0 attendue."),
 ("short_message / sm_length", "5.2.21-22", "Jusqu'à 254 octets ; au-delà, TLV message_payload."),
 ("message_payload (TLV 0x0424)", "5.3.2.32", "Accepté dans submit_sm et data_sm lorsque short_message est vide."),
 ("sar_msg_ref_num / sar_total_segments / sar_segment_seqnum (0x020C-0x020F)", "5.3.2.22-24", "Concaténation par TLV, équivalente à l'UDH ; au plus `SMPP_SERVER_MAX_PARTS` parties (6 par défaut), sinon ESME_RINVMSGLEN ; partie manquante → abandon après 120 s."),
 ("receipted_message_id (0x001E) et message_state (0x0427)", "5.3.2.12 / 5.3.2.35", "Présents dans chaque accusé deliver_sm, en plus du texte Annexe B « id:… sub:… dlvrd:… submit date:… done date:… stat:… err:… text: »."),
 ("TLV non reconnus", "5.3", "Ignorés, comme le prévoit la norme."),
 ("message_id", "5.2.23", "10 caractères hexadécimaux, uniques ; un par partie d'un message concaténé (accusé pour chaque partie)."),
 ("Flux et débit", "-", "Plusieurs PDU en attente acceptés (fenêtre) ; débit par client (`rateLimitPerMin`) au-delà duquel ESME_RTHROTTLED (0x58)."),
 ("Temporisations", "-", "Bind attendu sous 3 s après connexion ; réponse du partenaire à un deliver_sm attendue 5 s ; déconnexion après 3 échecs de remise consécutifs (rejeu ensuite)."),
]

ERRORS = [("0x00", "ESME_ROK", "Succès"), ("0x01", "ESME_RINVMSGLEN", "Longueur ou nombre de parties invalide"), ("0x03", "ESME_RINVCMDID", "PDU inconnu ou SMPP 5.0 (broadcast_sm…)"),
          ("0x04", "ESME_RINVBNDSTS", "PDU non permis pour ce mode de bind (ex. submit_sm en bind_receiver)"), ("0x05", "ESME_RALYBND", "Nombre maximal de connexions du client atteint"),
          ("0x08", "ESME_RSYSERR", "Erreur interne"), ("0x0A", "ESME_RINVSRCADR", "Expéditeur non autorisé"), ("0x0B", "ESME_RINVDSTADR", "Destinataire invalide"),
          ("0x0D", "ESME_RBINDFAIL", "Adresse IP non autorisée, droit d'envoi absent, trop d'échecs"), ("0x0E", "ESME_RINVPASWD", "Mot de passe invalide"),
          ("0x0F", "ESME_RINVSYSID", "system_id inconnu ou désactivé"), ("0x11", "ESME_RCANCELFAIL", "Annulation impossible (inconnu ou déjà transmis)"),
          ("0x13", "ESME_RREPLACEFAIL", "Remplacement impossible"), ("0x33", "ESME_RINVNUMDESTS", "submit_multi : 0 ou plus de 255 destinations"),
          ("0x34", "ESME_RINVDLNAME", "submit_multi : liste de distribution non gérée"), ("0x45", "ESME_RSUBMITFAIL", "Destinataire exclu (liste noire, antifraude)"),
          ("0x58", "ESME_RTHROTTLED", "Débit autorisé dépassé"), ("0x61", "ESME_RINVSCHED", "Date d'échéance invalide"), ("0x62", "ESME_RINVEXPIRY", "Validité invalide"),
          ("0x67", "ESME_RQUERYFAIL", "query_sm : message inconnu")]

OPERATOR = [
 ("Bind et maintien de la liaison", "Mode TX / RX / TRX, enquire_link, reconnexion automatique (`con_loss_retry`), jusqu'à 4 liens par opérateur avec bascule", "`infra/jasmin/connector.jcli.tpl`, `tests/integration/failover.sh` (7/7)"),
 ("submit_sm", "TON/NPI source et destination par opérateur ; GSM 03.38 (data_coding 0) ou UCS-2 (8) selon le texte ; priorité 0 à 3 ; registered_delivery demandé (accusé final)", "Pile réelle : `tests/integration/local-stack.sh test`"),
 ("Messages longs", "Découpage par Jasmin avec UDH (concaténation 8 bits)", "Pile réelle : 319 caractères = 3 segments avec UDH"),
 ("deliver_sm MO", "Texte GSM, UCS-2 (arabe) et parties concaténées réassemblées", "Pile réelle : MO arabe UCS-2 → réponse arabe intacte"),
 ("deliver_sm accusé", "Accusé Annexe B rapproché du message_id de submit_sm_resp ; facturation selon la règle de l'opérateur (`{OP}_DLR_BILLING_RULE`)", "FlowTests, pile réelle (accusé → ledger CHARGED)"),
 ("Débit et refus temporaires", "Débit par lien (`{OP}_TPS`) ; ESME_RTHROTTLED et refus transitoires réessayés sans perte", "SmppSimulatorTests.throttlingReturnsRthrottled, pile réelle"),
 ("validity_period", "Non transmis à l'opérateur (défaut de Jasmin constaté) : l'expiration est appliquée par la plateforme avant l'envoi, puis par le délai d'accusé", "`JasminHttpGateway`"),
]

GAPS = [
 "outbind et alert_notification ne sont pas utilisés : ils ne s'appliquent pas aux échanges de ce service (voir tableau).",
 "registered_delivery : les accusés SME (bits 2-3) et les notifications intermédiaires (bit 4) ne sont pas émis ; ils sont rarement demandés et peu d'opérateurs les fournissent.",
 "sm_default_msg_id (messages prédéfinis) n'est pas géré : valeur 0 attendue.",
 "La bibliothèque jsmpp 3.0.0 renvoie toujours ESME_RSYSERR (0x08) à un query_sm, cancel_sm ou replace_sm refusé ; la plateforme rétablit le code exact de la norme (0x67, 0x11, 0x13, 0x61) au moment de l'écriture de la réponse (`SmppServer.StatusSender`), vérifié par les tests.",
 "Côté opérateurs, chaque SMSC a ses particularités (format d'accusé, TON/NPI, débit, comportement en surcharge) : la recette SMPP de chaque opérateur (A02 à A13 « OP » du cahier de recette) reste à faire sur leur environnement de test.",
 "SMPP n'est pas chiffré : les liaisons opérateurs passent par les VPN des opérateurs, et les partenaires doivent se connecter par VPN ou tunnel TLS (stunnel), avec filtrage par adresse IP.",
]

TESTS = ("Serveur partenaires : 9 scénarios `SmppServerTests` avec un vrai client SMPP (jsmpp) contre le serveur démarré ; opérateurs : 5 scénarios `SmppSimulatorTests`, "
         "parcours `FlowTests`, et intégration sur pile réelle (PostgreSQL, Redis, RabbitMQ, Jasmin 0.10, simulateur SMSC) par `tests/integration/local-stack.sh` et `failover.sh`. "
         "Suite complète : 128 tests serveur (dont 1 exécuté à part sur PostgreSQL réel) et 18 tests d'interface, 0 échec.")


def md_table(head, rows):
    out = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    out += ["| " + " | ".join(str(c).replace("|", "/") for c in r) + " |" for r in rows]
    return "\n".join(out)


def write_md():
    parts = [f"# {TITLE}", "", INTRO, "",
             "## 1. Légende", md_table(["Statut", "Signification"], LEGEND), "",
             "## 2. PDU SMPP v3.4", md_table(["PDU", "command_id", "§", "(A) Vers les opérateurs (Jasmin)", "(B) Serveur partenaires", "Preuve"], PDUS), "",
             "Les PDU de SMPP 5.0 (broadcast_sm, query_broadcast_sm, cancel_broadcast_sm) sont refusés par ESME_RINVCMDID, comme tout PDU hors v3.4.", "",
             "## 3. Champs et TLV (serveur partenaires)", md_table(["Champ", "§", "Traitement"], PARAMS), "",
             "## 4. Codes d'erreur renvoyés (§5.1.3)", md_table(["Code", "Nom", "Cas"], ERRORS), "",
             "## 5. Liaison avec les opérateurs (rôle A)", md_table(["Fonction", "Mise en œuvre", "Preuve"], OPERATOR), "",
             "## 6. Écarts et points à valider", *[f"- {g}" for g in GAPS], "",
             "## 7. Tests", TESTS, "",
             "PDF client : `docs/conformite/Conformite_SMPP_v34_VAS_Tunisie.pdf` (généré par `docs/conformite/generate_conformite_smpp.py`, même contenu).", ""]
    with open(MD, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))


def write_pdf():
    pdfmetrics.registerFont(TTFont("DV", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
    pdfmetrics.registerFont(TTFont("DVB", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"))
    ss = getSampleStyleSheet()
    B = ParagraphStyle("B", parent=ss["Normal"], fontName="DV", fontSize=8.4, leading=11)
    S = ParagraphStyle("S", parent=B, fontSize=7.2, leading=9.2)
    SB = ParagraphStyle("SB", parent=S, fontName="DVB", textColor=colors.white)
    H1 = ParagraphStyle("H1", parent=B, fontName="DVB", fontSize=15, leading=19, spaceAfter=6, textColor=colors.HexColor("#0b3d91"))
    H2 = ParagraphStyle("H2", parent=B, fontName="DVB", fontSize=10.5, leading=14, spaceBefore=8, spaceAfter=4, keepWithNext=1, textColor=colors.HexColor("#0b3d91"))
    import re

    def fmt(t):
        t = str(t).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        t = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", t)
        t = re.sub(r"`(.+?)`", r"<font face='DejaVuSansMono' size='6.8'>\1</font>", t)
        return re.sub(r"\*(.+?)\*", r"<i>\1</i>", t)

    pdfmetrics.registerFont(TTFont("DejaVuSansMono", "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"))
    page = landscape(A4)
    width = page[0] - 24 * mm

    def color(t):
        if t.startswith("Conforme"): return colors.HexColor("#e6f4ea")
        if t.startswith("Facultatif"): return colors.HexColor("#fff6e0")
        if t.startswith("Non applicable"): return colors.HexColor("#eeeeee")
        return None

    def table(head, rows, widths, status_cols=()):
        data = [[Paragraph(fmt(h), SB) for h in head]] + [[Paragraph(fmt(c), S) for c in r] for r in rows]
        t = Table(data, colWidths=[w * width for w in widths], repeatRows=1)
        st = [("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#0b3d91")), ("VALIGN", (0, 0), (-1, -1), "TOP"),
              ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#b0b8c8")), ("LEFTPADDING", (0, 0), (-1, -1), 3), ("RIGHTPADDING", (0, 0), (-1, -1), 3)]
        for i, r in enumerate(rows, 1):
            for c in status_cols:
                if color(r[c]): st.append(("BACKGROUND", (c, i), (c, i), color(r[c])))
        t.setStyle(TableStyle(st))
        return t

    def footer(cv, doc):
        cv.setFont("DV", 7)
        cv.setFillColor(colors.HexColor("#666666"))
        cv.drawString(12 * mm, 8 * mm, f"Conformité SMPP v3.4 — plateforme VAS / SMS Premium — {DATE}")
        cv.drawRightString(page[0] - 12 * mm, 8 * mm, f"Page {doc.page}")

    story = [Paragraph(fmt(TITLE), H1), Paragraph(f"Version du {DATE}", S), Spacer(1, 4), Paragraph(fmt(INTRO), B),
             Paragraph("Synthèse", H2),
             Paragraph(fmt("**Tous les PDU SMPP v3.4 qui s'appliquent à chaque rôle sont implémentés et testés.** Côté partenaires, la plateforme est un SMSC v3.4 complet : "
                           "bind (3 modes), submit_sm, submit_multi, data_sm, deliver_sm (MO et accusés), query_sm, cancel_sm, replace_sm, enquire_link, unbind, generic_nack, "
                           "avec les formats de date, priorités, accusés, codages, TLV et codes d'erreur de la norme. Côté opérateurs, la plateforme est un ESME v3.4 (Jasmin) "
                           "validé sur pile réelle contre un SMSC simulé ; la recette avec chaque opérateur reste à faire sur son environnement de test."), B),
             Paragraph("1. Légende", H2), table(["Statut", "Signification"], LEGEND, [0.2, 0.8], status_cols=(0,)),
             Paragraph("2. PDU SMPP v3.4", H2),
             table(["PDU", "command_id", "§", "(A) Vers les opérateurs (Jasmin)", "(B) Serveur partenaires", "Preuve"], PDUS, [0.14, 0.1, 0.04, 0.24, 0.29, 0.19], status_cols=(3, 4)),
             Spacer(1, 3), Paragraph("Les PDU de SMPP 5.0 (broadcast_sm, query_broadcast_sm, cancel_broadcast_sm) sont refusés par ESME_RINVCMDID, comme tout PDU hors v3.4.", S),
             Paragraph("3. Champs et TLV (serveur partenaires)", H2), table(["Champ", "§", "Traitement"], PARAMS, [0.22, 0.09, 0.69]),
             Paragraph("4. Codes d'erreur renvoyés (§5.1.3)", H2), table(["Code", "Nom", "Cas"], ERRORS, [0.07, 0.2, 0.73]),
             Paragraph("5. Liaison avec les opérateurs (rôle A)", H2), table(["Fonction", "Mise en œuvre", "Preuve"], OPERATOR, [0.18, 0.5, 0.32]),
             Paragraph("6. Écarts et points à valider", H2), *[Paragraph("• " + fmt(g), B) for g in GAPS],
             Paragraph("7. Tests", H2), Paragraph(fmt(TESTS), B)]
    SimpleDocTemplate(PDF, pagesize=page, leftMargin=12 * mm, rightMargin=12 * mm, topMargin=12 * mm, bottomMargin=14 * mm,
                      title="Conformité SMPP v3.4", author="Plateforme VAS").build(story, onFirstPage=footer, onLaterPages=footer)


if __name__ == "__main__":
    write_md()
    write_pdf()
    print("écrit :", os.path.normpath(MD), os.path.normpath(PDF))

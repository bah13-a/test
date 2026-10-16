package tn.vas.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jsmpp.bean.*;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.session.*;

/**
 * Contrôle de conformité SMPP v3.4 d'un serveur, vu de l'extérieur (recette partenaires, étape 2 du passage en production).
 * Rejoue les PDU de la matrice docs/22 contre le serveur indiqué et affiche OK / ÉCHEC par cas. Les envois partent réellement
 * vers les numéros de test (SMS facturés) ; le message programmé est annulé avant échéance.
 * <pre>
 * java -Dloader.main=tn.vas.tools.SmppCheck -cp vas-platform.jar org.springframework.boot.loader.launch.PropertiesLauncher \
 *      host port system_id mot_de_passe expéditeur numéro_test [numéro_test_2] [attente_accusé_s=60]
 * </pre>
 */
public final class SmppCheck {
    private final List<String[]> results = new ArrayList<>();
    private final BlockingQueue<DeliverSm> receipts = new LinkedBlockingQueue<>();
    private final String source;

    private SmppCheck(String source) { this.source = source; }

    /** Session jsmpp exposant l'envoi d'un enquire_link à la demande. */
    private static final class Probe extends SMPPSession {
        void ping() throws Exception { sendEnquireLink(); }
    }

    public static void main(String[] a) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8));
        if (a.length < 6) {
            System.err.println("usage : SmppCheck host port system_id mot_de_passe expéditeur numéro_test [numéro_test_2] [attente_accusé_s=60]");
            System.exit(2);
        }
        String dest2 = a.length > 6 ? a[6] : a[5];
        int wait = a.length > 7 ? Integer.parseInt(a[7]) : 60;
        System.exit(new SmppCheck(a[4]).run(a[0], Integer.parseInt(a[1]), a[2], a[3], a[5], dest2, wait) ? 0 : 1);
    }

    private boolean run(String host, int port, String sys, String pw, String dest, String dest2, int wait) throws Exception {
        var s = new Probe();
        s.setMessageReceiverListener(new MessageReceiverListener() {
            @Override public void onAcceptDeliverSm(DeliverSm d) { if (MessageType.SMSC_DEL_RECEIPT.containedIn(d.getEsmClass())) receipts.add(d); }
            @Override public void onAcceptAlertNotification(AlertNotification n) { }
            @Override public DataSmResult onAcceptDataSm(DataSm d, Session x) { return null; }
        });
        try {
            s.connectAndBind(host, port, new BindParameter(BindType.BIND_TRX, sys, pw, "VAS", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null));
            ok("4.1", "bind_transceiver (interface_version 0x34)", "accepté");
        } catch (Exception e) {
            ko("4.1", "bind_transceiver", e.getMessage());
            return report();
        }
        check("4.11", "enquire_link", () -> { s.ping(); return "réponse reçue"; });

        String id1 = check("4.4", "submit_sm GSM 03.38 avec accusé (registered_delivery 1)", () ->
                submit(s, dest, null, null, 1, 0, "Controle SMPP v3.4 : message GSM".getBytes(StandardCharsets.ISO_8859_1)));
        check("4.4", "submit_sm UCS-2 (data_coding 8)", () ->
                submit(s, dest, null, null, 0, 8, "اختبار SMPP v3.4 — é à ç".getBytes(StandardCharsets.UTF_16BE)));
        check("4.4", "submit_sm long : 2 parties UDH (esm_class 0x40)", () -> {
            String a = udh(s, dest, 1, "Controle SMPP : premiere partie, "), b = udh(s, dest, 2, "seconde partie.");
            return a + " / " + b;
        });
        check("4.5", "submit_multi (2 destinations)", () -> {
            var r = s.submitMultiple("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, source,
                    new Address[]{addr(dest), addr(dest2)}, new ESMClass(), (byte) 0, (byte) 1, null, null, new RegisteredDelivery(0),
                    ReplaceIfPresentFlag.DEFAULT, new GeneralDataCoding(Alphabet.ALPHA_DEFAULT), (byte) 0, "Controle SMPP : submit_multi".getBytes(StandardCharsets.ISO_8859_1));
            if (r.getUnsuccessDeliveries() != null && r.getUnsuccessDeliveries().length > 0)
                throw new IllegalStateException(r.getUnsuccessDeliveries().length + " destination(s) refusée(s), code 0x" + Integer.toHexString(r.getUnsuccessDeliveries()[0].getErrorStatusCode()));
            return r.getMessageId();
        });
        check("4.7", "data_sm (message_payload)", () -> s.dataShortMessage("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, source,
                TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, dest, new ESMClass(), new RegisteredDelivery(0), new GeneralDataCoding(Alphabet.ALPHA_IA5),
                new OptionalParameter.OctetString(OptionalParameter.Tag.MESSAGE_PAYLOAD.code(), "Controle SMPP : data_sm".getBytes(StandardCharsets.US_ASCII))).getMessageId());

        String sched = check("4.4", "submit_sm programmé (échéance relative +1 h, §7.1.1)", () ->
                submit(s, dest, "000000010000000R", null, 1, 0, "Controle SMPP : programme".getBytes(StandardCharsets.ISO_8859_1)));
        if (sched != null) {
            check("4.8", "query_sm → SCHEDULED", () -> expectState(s, sched, MessageState.SCHEDULED));
            check("4.10", "replace_sm", () -> {
                s.replaceShortMessage(sched, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, source, null, null, new RegisteredDelivery(1), (byte) 0,
                        "Controle SMPP : remplace".getBytes(StandardCharsets.ISO_8859_1));
                return "accepté";
            });
            check("4.9", "cancel_sm", () -> {
                s.cancelShortMessage("", sched, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, source, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, dest);
                return "accepté";
            });
            check("4.8", "query_sm → DELETED après annulation", () -> expectState(s, sched, MessageState.DELETED));
            check("5.1.3", "cancel_sm déjà annulé → ESME_RCANCELFAIL (0x11)", () -> expectError(0x11, () -> {
                s.cancelShortMessage("", sched, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, source, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, dest);
                return null;
            }));
        }
        check("5.1.3", "query_sm inconnu → ESME_RQUERYFAIL (0x67)", () -> expectError(0x67, () ->
                s.queryShortMessage("0000000000", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, source)));
        check("5.1.3", "destination invalide → ESME_RINVDSTADR (0x0B)", () -> expectError(0x0B, () ->
                submit(s, "12", null, null, 0, 0, "x".getBytes(StandardCharsets.ISO_8859_1))));
        check("5.1.3", "validité invalide → ESME_RINVEXPIRY (0x62)", () -> expectError(0x62, () ->
                submit(s, dest, null, "991399999999000+", 0, 0, "x".getBytes(StandardCharsets.ISO_8859_1))));

        if (id1 != null) check("4.6", "accusé deliver_sm (esm_class 0x04, TLV receipted_message_id et message_state)", () -> {
            long end = System.currentTimeMillis() + wait * 1000L;
            while (System.currentTimeMillis() < end) {
                DeliverSm d = receipts.poll(1, TimeUnit.SECONDS);
                if (d == null) continue;
                var rid = d.getOptionalParameter(OptionalParameter.Tag.RECEIPTED_MESSAGE_ID);
                var st = d.getOptionalParameter(OptionalParameter.Tag.MESSAGE_STATE);
                if (rid instanceof OptionalParameter.COctetString c && id1.equals(c.getValueAsString())) {
                    if (st == null) throw new IllegalStateException("TLV message_state absent");
                    return MessageState.valueOf(((OptionalParameter.Byte) st).getValue()) + " — " + new String(d.getShortMessage(), StandardCharsets.ISO_8859_1);
                }
            }
            throw new IllegalStateException("aucun accusé en " + wait + " s (le téléphone de test est-il allumé ?)");
        });
        check("4.2", "unbind", () -> { s.unbindAndClose(); return "fermé proprement"; });
        return report();
    }

    private String submit(SMPPSession s, String to, String schedule, String validity, int registered, int coding, byte[] body) throws Exception {
        return s.submitShortMessage("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, source, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, to,
                new ESMClass(), (byte) 0, (byte) 1, schedule, validity, new RegisteredDelivery((byte) registered), (byte) 0,
                coding == 8 ? new GeneralDataCoding(Alphabet.ALPHA_UCS2) : new GeneralDataCoding(Alphabet.ALPHA_DEFAULT), (byte) 0, body).getMessageId();
    }

    private String udh(SMPPSession s, String to, int seq, String text) throws Exception {
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1), b = new byte[6 + t.length];
        b[0] = 5; b[1] = 0; b[2] = 3; b[3] = 0x2a; b[4] = 2; b[5] = (byte) seq;
        System.arraycopy(t, 0, b, 6, t.length);
        return s.submitShortMessage("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, source, TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, to,
                new ESMClass((byte) 0x40), (byte) 0, (byte) 1, null, null, new RegisteredDelivery(0), (byte) 0,
                new GeneralDataCoding(Alphabet.ALPHA_DEFAULT), (byte) 0, b).getMessageId();
    }

    private static Address addr(String n) { return new Address(TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, n); }

    private String expectState(SMPPSession s, String id, MessageState want) throws Exception {
        var q = s.queryShortMessage(id, TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, source);
        if (q.getMessageState() != want) throw new IllegalStateException("état " + q.getMessageState() + ", attendu " + want);
        return want + (q.getFinalDate() == null ? "" : ", final_date " + q.getFinalDate());
    }

    private interface Call { Object run() throws Exception; }

    private static String expectError(int code, Call c) throws Exception {
        try {
            c.run();
        } catch (NegativeResponseException e) {
            if (e.getCommandStatus() == code) return String.format("0x%02X reçu", code);
            throw new IllegalStateException(String.format("0x%02X reçu, attendu 0x%02X", e.getCommandStatus(), code));
        }
        throw new IllegalStateException("accepté, refus attendu");
    }

    private interface Step { String run() throws Exception; }

    private String check(String ref, String label, Step st) {
        try {
            String r = st.run();
            ok(ref, label, r);
            return r;
        } catch (Exception e) {
            ko(ref, label, e instanceof NegativeResponseException n ? String.format("refus 0x%02X", n.getCommandStatus()) : String.valueOf(e.getMessage()));
            return null;
        }
    }

    private void ok(String ref, String label, String detail) { results.add(new String[]{"OK", ref, label, detail}); }
    private void ko(String ref, String label, String detail) { results.add(new String[]{"ÉCHEC", ref, label, detail}); }

    private boolean report() {
        System.out.println("| Résultat | § | Contrôle | Détail |");
        System.out.println("|---|---|---|---|");
        results.forEach(r -> System.out.println("| " + String.join(" | ", r).replace("\n", " ") + " |"));
        long ko = results.stream().filter(r -> r[0].equals("ÉCHEC")).count();
        System.out.println();
        System.out.println((results.size() - ko) + " / " + results.size() + " contrôles SMPP v3.4 OK");
        return ko == 0;
    }
}

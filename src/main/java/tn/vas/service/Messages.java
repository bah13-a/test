package tn.vas.service;

import java.util.Map;

/** Catalogue de messages système FR / AR / EN (surchargeables par service via service_reply). */
public final class Messages {
    private Messages() {}

    public static final String OK = "OK", STOP = "STOP", HELP = "HELP", LIMIT = "LIMIT", CLOSED = "CLOSED",
            CONFIRM = "CONFIRM", ALREADY = "ALREADY", RENEWAL = "RENEWAL", SUB_OK = "SUB_OK";

    private static final Map<String, Map<String, String>> CATALOG = Map.of(
        "fr", Map.of(OK, "Merci, votre message a bien été reçu.", STOP, "Vous êtes désabonné. Aucun renouvellement ne sera effectué.",
            HELP, "Envoyez STOP pour vous désabonner.", LIMIT, "Limite de participations atteinte.", CLOSED, "Ce service est actuellement indisponible.",
            CONFIRM, "Répondez OUI pour confirmer votre abonnement à %s. STOP pour annuler.", ALREADY, "Vous êtes déjà abonné.",
            RENEWAL, "Renouvellement de votre abonnement %s. STOP pour vous désabonner.", SUB_OK, "Abonnement activé. STOP pour vous désabonner."),
        "en", Map.of(OK, "Thank you, your message has been received.", STOP, "You are unsubscribed. No further renewals will occur.",
            HELP, "Send STOP to unsubscribe.", LIMIT, "Participation limit reached.", CLOSED, "This service is currently unavailable.",
            CONFIRM, "Reply YES to confirm your subscription to %s. STOP to cancel.", ALREADY, "You are already subscribed.",
            RENEWAL, "Your %s subscription is renewed. STOP to unsubscribe.", SUB_OK, "Subscription activated. STOP to unsubscribe."),
        "ar", Map.of(OK, "شكرا، تم استلام رسالتك.", STOP, "تم إلغاء اشتراكك. لن يتم أي تجديد.",
            HELP, "أرسل STOP لإلغاء الاشتراك.", LIMIT, "تم بلوغ الحد الأقصى للمشاركات.", CLOSED, "الخدمة غير متوفرة حاليا.",
            CONFIRM, "أجب بنعم لتأكيد اشتراكك في %s. أرسل STOP للإلغاء.", ALREADY, "أنت مشترك بالفعل.",
            RENEWAL, "تم تجديد اشتراكك في %s. أرسل STOP لإلغاء الاشتراك.", SUB_OK, "تم تفعيل الاشتراك. أرسل STOP لإلغاء الاشتراك."));

    public static String text(String lang, String kind) {
        return CATALOG.getOrDefault(lang, CATALOG.get("fr")).get(kind);
    }

    /** Langue du message entrant : arabe si caractères arabes, anglais si mots-clés anglais, sinon langue par défaut du service. */
    public static String detect(String content, String fallback) {
        if (content != null) {
            for (char c : content.toCharArray()) if (c >= 0x0600 && c <= 0x06FF) return "ar";
            String u = content.trim().toUpperCase();
            if (u.startsWith("YES") || u.startsWith("HELP") || u.startsWith("UNSUBSCRIBE")) return "en";
        }
        return fallback == null ? "fr" : fallback;
    }
}

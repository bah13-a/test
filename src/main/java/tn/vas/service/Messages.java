package tn.vas.service;

import java.util.Map;

/** Catalogue de messages système FR / AR / EN (surchargeables par service via service_reply). */
public final class Messages {
    private Messages() {}

    public static final String OK = "OK", STOP = "STOP", HELP = "HELP", LIMIT = "LIMIT", CLOSED = "CLOSED",
            CONFIRM = "CONFIRM", ALREADY = "ALREADY", RENEWAL = "RENEWAL", SUB_OK = "SUB_OK",
            INVALID_CHOICE = "INVALID_CHOICE", VOTE_OK = "VOTE_OK", QUIZ_QUESTION = "QUIZ_QUESTION", QUIZ_CORRECT = "QUIZ_CORRECT",
            QUIZ_WRONG = "QUIZ_WRONG", QUIZ_DONE = "QUIZ_DONE", QUIZ_PLAYED = "QUIZ_PLAYED", CONTENT_LINK = "CONTENT_LINK", CONTENT_UNKNOWN = "CONTENT_UNKNOWN";

    private static final Map<String, Map<String, String>> CATALOG = Map.of(
        "fr", m(OK, "Merci, votre message a bien été reçu.", STOP, "Vous êtes désabonné. Aucun renouvellement ne sera effectué.",
            HELP, "Envoyez STOP pour vous désabonner.", LIMIT, "Limite de participations atteinte.", CLOSED, "Ce service est actuellement indisponible.",
            CONFIRM, "Répondez OUI pour confirmer votre abonnement à %s. STOP pour annuler.", ALREADY, "Vous êtes déjà abonné.",
            RENEWAL, "Renouvellement de votre abonnement %s. STOP pour vous désabonner.", SUB_OK, "Abonnement activé. STOP pour vous désabonner.",
            INVALID_CHOICE, "Choix invalide. Choix possibles : %s", VOTE_OK, "Merci, votre vote pour %s est enregistré.", QUIZ_QUESTION, "Question %d/%d : %s",
            QUIZ_CORRECT, "Bonne réponse !", QUIZ_WRONG, "Mauvaise réponse.", QUIZ_DONE, "Quiz terminé. Votre score : %d/%d.", QUIZ_PLAYED, "Vous avez déjà participé à ce quiz.",
            CONTENT_LINK, "Votre contenu : %s (valable %d h, %d utilisation(s)).", CONTENT_UNKNOWN, "Contenu inconnu. Contenus disponibles : %s"),
        "en", m(OK, "Thank you, your message has been received.", STOP, "You are unsubscribed. No further renewals will occur.",
            HELP, "Send STOP to unsubscribe.", LIMIT, "Participation limit reached.", CLOSED, "This service is currently unavailable.",
            CONFIRM, "Reply YES to confirm your subscription to %s. STOP to cancel.", ALREADY, "You are already subscribed.",
            RENEWAL, "Your %s subscription is renewed. STOP to unsubscribe.", SUB_OK, "Subscription activated. STOP to unsubscribe.",
            INVALID_CHOICE, "Invalid choice. Valid choices: %s", VOTE_OK, "Thank you, your vote for %s is recorded.", QUIZ_QUESTION, "Question %d/%d: %s",
            QUIZ_CORRECT, "Correct!", QUIZ_WRONG, "Wrong answer.", QUIZ_DONE, "Quiz finished. Your score: %d/%d.", QUIZ_PLAYED, "You have already played this quiz.",
            CONTENT_LINK, "Your content: %s (valid %d h, %d use(s)).", CONTENT_UNKNOWN, "Unknown content. Available: %s"),
        "ar", m(OK, "شكرا، تم استلام رسالتك.", STOP, "تم إلغاء اشتراكك. لن يتم أي تجديد.",
            HELP, "أرسل STOP لإلغاء الاشتراك.", LIMIT, "تم بلوغ الحد الأقصى للمشاركات.", CLOSED, "الخدمة غير متوفرة حاليا.",
            CONFIRM, "أجب بنعم لتأكيد اشتراكك في %s. أرسل STOP للإلغاء.", ALREADY, "أنت مشترك بالفعل.",
            RENEWAL, "تم تجديد اشتراكك في %s. أرسل STOP لإلغاء الاشتراك.", SUB_OK, "تم تفعيل الاشتراك. أرسل STOP لإلغاء الاشتراك.",
            INVALID_CHOICE, "اختيار غير صالح. الاختيارات الممكنة: %s", VOTE_OK, "شكرا، تم تسجيل تصويتك لـ %s.", QUIZ_QUESTION, "السؤال %d/%d: %s",
            QUIZ_CORRECT, "إجابة صحيحة!", QUIZ_WRONG, "إجابة خاطئة.", QUIZ_DONE, "انتهى الاختبار. نتيجتك: %d/%d.", QUIZ_PLAYED, "لقد شاركت في هذا الاختبار من قبل.",
            CONTENT_LINK, "المحتوى الخاص بك: %s (صالح %d ساعة، %d استخدام).", CONTENT_UNKNOWN, "محتوى غير معروف. المتاح: %s"));

    /** Construit un catalogue à partir de paires clé/valeur (Map.of est limité à 10 entrées). */
    private static Map<String, String> m(String... kv) {
        var map = new java.util.HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return Map.copyOf(map);
    }

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

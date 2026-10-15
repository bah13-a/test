package tn.vas.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Menu interactif USSD / IVR décrit en JSON. Un nœud : {"text": "...", "options": {"1": {...}, "2": {...}}} ou une feuille
 * {"text": "...", "action": "END" | "KEYWORD" | "CHARGE", "param": "..."}.
 * <ul>
 *   <li>END (défaut) : affiche / prononce le texte et termine ;</li>
 *   <li>KEYWORD : envoie « param » comme un MO au short code du service (vote, quiz, abonnement, contenu… réutilisent les moteurs SMS ;
 *       la réponse part par SMS) ;</li>
 *   <li>CHARGE : facture une fois le tarif USSD / IVR approuvé du service, puis affiche le texte.</li>
 * </ul>
 * Un chemin de navigation est la suite des touches séparées par « * » (convention des passerelles USSD HTTP), ex. « 1*2 ».
 */
public final class MenuTree {
    public static final int MAX_DEPTH = 8, MAX_USSD_TEXT = 182;
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Action { END, KEYWORD, CHARGE }

    public record Node(String text, Map<String, Node> options, Action action, String param) {
        public boolean leaf() { return options.isEmpty(); }
    }

    /** Résultat de navigation : nœud atteint, chemin valide retenu et indicateur de touche invalide (le nœud parent est alors réaffiché). */
    public record Position(Node node, String path, boolean invalid) {}

    private final Node root;

    private MenuTree(Node root) { this.root = root; }

    public Node root() { return root; }

    /** Lecture + validation ; IllegalArgumentException avec la liste des erreurs si le menu est invalide. */
    public static MenuTree parse(String json, boolean ussd) {
        JsonNode n;
        try {
            n = JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON invalide : " + e.getMessage());
        }
        List<String> errors = new ArrayList<>();
        Node root = node(n, "racine", 0, ussd, errors);
        if (root != null && root.leaf()) errors.add("racine : au moins une option est requise");
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join(" ; ", errors));
        return new MenuTree(root);
    }

    private static Node node(JsonNode n, String where, int depth, boolean ussd, List<String> errors) {
        if (n == null || !n.isObject()) { errors.add(where + " : objet attendu"); return null; }
        if (depth > MAX_DEPTH) { errors.add(where + " : profondeur maximale " + MAX_DEPTH + " dépassée"); return null; }
        String text = n.path("text").asText("").strip();
        if (text.isEmpty()) errors.add(where + " : texte obligatoire");
        if (ussd && text.length() > MAX_USSD_TEXT) errors.add(where + " : texte USSD limité à " + MAX_USSD_TEXT + " caractères (" + text.length() + ")");
        Map<String, Node> options = new LinkedHashMap<>();
        JsonNode o = n.get("options");
        if (o != null && !o.isNull()) {
            if (!o.isObject()) errors.add(where + " : options doit être un objet");
            else o.fields().forEachRemaining(f -> {
                if (!f.getKey().matches("[0-9#]")) errors.add(where + " : touche « " + f.getKey() + " » invalide (0-9 ou #)");
                else {
                    Node child = node(f.getValue(), where + "/" + f.getKey(), depth + 1, ussd, errors);
                    if (child != null) options.put(f.getKey(), child);
                }
            });
        }
        Action action = Action.END;
        String a = n.path("action").asText("");
        if (!a.isEmpty()) {
            try { action = Action.valueOf(a); } catch (IllegalArgumentException e) { errors.add(where + " : action « " + a + " » inconnue (END, KEYWORD, CHARGE)"); }
        }
        String param = n.path("param").asText("").strip();
        if (!options.isEmpty() && action != Action.END && !a.isEmpty()) errors.add(where + " : une action n'est possible que sur une feuille");
        if (action == Action.KEYWORD && param.isEmpty()) errors.add(where + " : KEYWORD exige « param » (texte envoyé au short code)");
        return new Node(text, options, action, param.isEmpty() ? null : param);
    }

    /** Suit le chemin ; la première touche invalide arrête la navigation sur son nœud parent (invalid = true). */
    public Position at(String path) {
        Node cur = root;
        StringBuilder valid = new StringBuilder();
        if (path == null || path.isBlank()) return new Position(root, "", false);
        for (String step : path.split("\\*")) {
            if (step.isEmpty()) continue;
            Node next = cur.options().get(step);
            if (next == null) return new Position(cur, valid.toString(), true);
            if (!valid.isEmpty()) valid.append('*');
            valid.append(step);
            cur = next;
            if (cur.leaf()) break;   // une feuille termine la session : les touches suivantes sont ignorées
        }
        return new Position(cur, valid.toString(), false);
    }
}

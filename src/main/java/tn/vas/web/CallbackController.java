package tn.vas.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.vas.service.DlrService;
import tn.vas.service.MoService;

/** Points d'entrée appelés par Jasmin (http connector MO et DLR). Réponse "ACK/Jasmin" = message acquitté. */
@RestController
@RequestMapping("/callbacks")
public class CallbackController {
    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);
    private final MoService mo;
    private final DlrService dlr;

    public CallbackController(MoService mo, DlrService dlr) {
        this.mo = mo;
        this.dlr = dlr;
    }

    @RequestMapping(value = "/mo", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<String> mo(@RequestParam(value = "id", required = false) String id,
                                     @RequestParam("from") String from, @RequestParam("to") String to,
                                     @RequestParam(value = "content", required = false) String content,
                                     @RequestParam(value = "binary", required = false) String binary,
                                     @RequestParam(value = "coding", required = false) String coding,
                                     @RequestParam("origin-connector") String connector) {
        try {
            // Jasmin joint le contenu en hexadécimal + data_coding : seul moyen fiable pour l'arabe (UCS-2) et les accents
            String text = tn.vas.service.MoDecoder.decode(binary, tn.vas.service.MoDecoder.parseCoding(coding), content);
            var outcome = mo.handle(connector, from, to, text, id);
            log.info("MO {} -> {}", id, outcome);
            return ResponseEntity.ok("ACK/Jasmin");
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.info("MO concurrent déjà enregistré (doublon) : {}", id);
            return ResponseEntity.ok("ACK/Jasmin"); // la contrainte unique a neutralisé le doublon : ne pas rejouer
        } catch (IllegalArgumentException e) {
            log.warn("MO rejeté : {}", e.getMessage());
            return ResponseEntity.ok("ACK/Jasmin"); // données invalides : inutile de rejouer
        }
        // toute autre exception → HTTP 500 → Jasmin rejoue (MO-007) ; le dédoublonnage protège du double effet
    }

    @RequestMapping(value = "/dlr", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<String> dlr(@RequestParam("cid") String cid, @RequestParam("message_status") String status) {
        boolean changed = dlr.process(cid, status);
        log.info("DLR cid={} status={} changed={}", cid, status, changed);
        return ResponseEntity.ok("ACK/Jasmin");
    }
}

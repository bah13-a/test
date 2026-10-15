package tn.vas.web;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.vas.channel.IvrService;
import tn.vas.channel.UssdService;

/**
 * Passerelles USSD et IVR (même protection que les callbacks Jasmin : secret partagé, VPN opérateur). Paramètres acceptés sous les
 * noms usuels des passerelles (sessionId / phoneNumber / serviceCode / text).
 */
@RestController
@RequestMapping("/callbacks")
public class ChannelCallbackController {
    private final UssdService ussd;
    private final IvrService ivr;

    public ChannelCallbackController(UssdService ussd, IvrService ivr) {
        this.ussd = ussd;
        this.ivr = ivr;
    }

    /** Réponse text/plain : « CON texte » (attendre une saisie) ou « END texte » (fin de session). */
    @RequestMapping(value = "/ussd", method = {RequestMethod.POST, RequestMethod.GET}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> ussd(@RequestParam("sessionId") String sessionId,
                                       @RequestParam(value = "phoneNumber", required = false) String phoneNumber,
                                       @RequestParam(value = "msisdn", required = false) String msisdn,
                                       @RequestParam("serviceCode") String serviceCode,
                                       @RequestParam(value = "text", required = false) String text,
                                       @RequestParam(value = "input", required = false) String input) {
        return ResponseEntity.ok(ussd.handle(sessionId, phoneNumber != null ? phoneNumber : msisdn, serviceCode, text, input).wire());
    }

    /** event = start | input | hangup ; réponse JSON {action: prompt | hangup, text, lang, maxDigits}. */
    @RequestMapping(value = "/ivr", method = {RequestMethod.POST, RequestMethod.GET}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ivr(@RequestParam("callId") String callId, @RequestParam("msisdn") String msisdn,
                                   @RequestParam(value = "number", required = false) String number,
                                   @RequestParam(value = "event", defaultValue = "input") String event,
                                   @RequestParam(value = "digits", required = false) String digits,
                                   @RequestParam(value = "duration", required = false) Integer duration) {
        var r = ivr.handle(callId, msisdn, number, event, digits, duration);
        return Map.of("action", r.action(), "text", r.text(), "lang", r.lang(), "maxDigits", r.maxDigits());
    }
}

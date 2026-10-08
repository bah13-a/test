package tn.vas.gateway;

import tn.vas.domain.MtMessage;

/** Abstraction de la gateway télécom : Jasmin par défaut, Kannel ou autre remplaçable sans toucher au coeur métier. */
public interface SmsGateway {

    record SendResult(boolean accepted, String smscMessageId, String error, boolean retryable) {
        public static SendResult ok(String id) { return new SendResult(true, id, null, false); }
        public static SendResult fail(String err, boolean retryable) { return new SendResult(false, null, err, retryable); }
    }

    SendResult send(MtMessage mt);
}

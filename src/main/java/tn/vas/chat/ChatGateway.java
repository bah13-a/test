package tn.vas.chat;

import tn.vas.domain.MtMessage;
import tn.vas.gateway.SmsGateway.SendResult;

/** Envoi d'un message sur un canal de messagerie enrichie (WhatsApp, RCS). */
public interface ChatGateway {
    SendResult send(MtMessage mt);
}

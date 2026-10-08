package tn.vas.service;

import tn.vas.domain.Enums.Priority;

public interface MtQueue {
    /** Publie l'ID de corrélation à traiter (appelé après commit de la transaction qui a persisté le MT). */
    void publish(String correlationId, Priority priority);
}

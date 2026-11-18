package coworking.reservations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

// Une ligne de la table reservation, telle que l'API la renvoie.
// Les colonnes salle_id, cree_le... alimentent salleId, creeLe...
public record Reservation(
    String id,
    String salleId,
    String utilisateur,
    LocalDateTime debut,
    LocalDateTime fin,
    int participants,
    BigDecimal montant,
    String statut,
    OffsetDateTime creeLe) {
}

package coworking.reservations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/reservations")
public class ReservationController {

  // Corps attendu par POST /reservations. Integer et non int : un champ
  // absent reste null et atteint le controle explicite plus bas (avec un
  // int, Jackson refuserait le corps avec un message generique)
  record Demande(String salleId, LocalDateTime debut, LocalDateTime fin,
      Integer participants) {
  }

  // Ce que l'on retient de la reponse du catalogue
  record Salle(String id, int capacite, BigDecimal prixHoraire) {
  }

  // Ce que l'on envoie au service disponibilites
  record Occupation(String reservationId, String salleId,
      LocalDateTime debut, LocalDateTime fin) {
  }

  // Corps d'erreur des autres services : {"detail": "..."}
  record Erreur(String detail) {
  }

  private final RestClient catalogue;
  private final RestClient disponibilites;
  private final JdbcClient base;

  ReservationController(RestClient.Builder http, JdbcClient base,
      @Value("${services.catalogue}") String urlCatalogue,
      @Value("${services.disponibilites}") String urlDisponibilites) {
    this.catalogue = http.clone().baseUrl(urlCatalogue).build();
    this.disponibilites = http.clone().baseUrl(urlDisponibilites).build();
    this.base = base;
  }

  // L'utilisateur est celui du jeton, transmis par la passerelle
  @GetMapping
  List<Reservation> lister(
      @RequestHeader("X-Utilisateur") String utilisateur) {
    return base.sql("SELECT * FROM reservation WHERE utilisateur = ?"
        + " ORDER BY debut")
        .param(utilisateur)
        .query(Reservation.class)
        .list();
  }

  @GetMapping("/{id}")
  Reservation lire(@PathVariable String id,
      @RequestHeader("X-Utilisateur") String utilisateur) {
    return base.sql("SELECT * FROM reservation"
        + " WHERE id = ? AND utilisateur = ?")
        .params(id, utilisateur)
        .query(Reservation.class)
        .optional()
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Réservation inconnue : " + id));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  Reservation reserver(@RequestBody Demande demande,
      @RequestHeader("X-Utilisateur") String utilisateur) {
    if (demande.salleId() == null || demande.debut() == null
        || demande.fin() == null || demande.participants() == null
        || demande.participants() < 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "salle_id, debut, fin et participants sont obligatoires");
    }

    // 1. Appel synchrone au catalogue : la salle et sa capacite
    Salle salle;
    try {
      salle = catalogue.get()
          .uri("/salles/{id}", demande.salleId())
          .retrieve()
          .body(Salle.class);
    } catch (HttpClientErrorException.NotFound e) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
          "Salle inconnue : " + demande.salleId());
    }
    if (demande.participants() > salle.capacite()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
          "Capacité dépassée : " + salle.capacite() + " places au plus");
    }

    // 2. Appel synchrone aux disponibilites : bloquer le creneau
    String id = UUID.randomUUID().toString();
    try {
      disponibilites.post()
          .uri("/occupations")
          .body(new Occupation(id, demande.salleId(), demande.debut(),
              demande.fin()))
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException e) {
      // 409 (creneau pris) ou 422 (hors horaires) : on relaie le refus
      Erreur erreur = e.getResponseBodyAs(Erreur.class);
      throw new ResponseStatusException(e.getStatusCode(),
          erreur == null ? null : erreur.detail());
    }

    // 3. Enregistrer la reservation dans la base du service
    long minutes = Duration.between(demande.debut(), demande.fin())
        .toMinutes();
    BigDecimal montant = salle.prixHoraire()
        .multiply(BigDecimal.valueOf(minutes))
        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    Reservation reservation = base.sql("""
        INSERT INTO reservation (id, salle_id, utilisateur, debut, fin,
                                 participants, montant, statut)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'CONFIRMEE')
        RETURNING *""")
        .params(id, demande.salleId(), utilisateur, demande.debut(),
            demande.fin(), demande.participants(), montant)
        .query(Reservation.class)
        .single();
    return reservation;
  }
}

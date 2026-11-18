package coworking.reservations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/reservations")
public class ReservationController {

  private static final Logger log =
      LoggerFactory.getLogger(ReservationController.class);

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
  private final RabbitTemplate rabbit;
  private final Retry tentativesCatalogue;
  private final Retry tentativesDisponibilites;
  private final CircuitBreaker circuitCatalogue;
  private final CircuitBreaker circuitDisponibilites;

  ReservationController(RestClient.Builder http, JdbcClient base,
      RabbitTemplate rabbit, RetryRegistry tentatives,
      CircuitBreakerRegistry circuits,
      @Value("${services.catalogue}") String urlCatalogue,
      @Value("${services.disponibilites}") String urlDisponibilites) {
    this.catalogue = http.clone().baseUrl(urlCatalogue).build();
    this.disponibilites = http.clone().baseUrl(urlDisponibilites).build();
    this.base = base;
    this.rabbit = rabbit;
    // Reglages lus dans application.yml (resilience4j.*)
    this.tentativesCatalogue = journaliser(tentatives.retry("catalogue"));
    this.tentativesDisponibilites =
        journaliser(tentatives.retry("disponibilites"));
    this.circuitCatalogue = journaliser(circuits.circuitBreaker("catalogue"));
    this.circuitDisponibilites =
        journaliser(circuits.circuitBreaker("disponibilites"));
  }

  // Ordre des protections : tentatives(coupe-circuit(appel)).
  // Chaque tentative passe par le coupe-circuit, qui compte les echecs.
  private static <T> T proteger(Retry tentatives, CircuitBreaker circuit,
      Supplier<T> appel) {
    return Retry.decorateSupplier(tentatives,
        CircuitBreaker.decorateSupplier(circuit, appel)).get();
  }

  private static Retry journaliser(Retry tentatives) {
    tentatives.getEventPublisher().onRetry(e -> log.warn(
        "{} : tentative {} en echec ({}), nouvel essai dans {} ms",
        e.getName(), e.getNumberOfRetryAttempts(),
        e.getLastThrowable().getClass().getSimpleName(),
        e.getWaitInterval().toMillis()));
    return tentatives;
  }

  private static CircuitBreaker journaliser(CircuitBreaker circuit) {
    circuit.getEventPublisher().onStateTransition(e -> log.warn(
        "Coupe-circuit {} : {}", e.getCircuitBreakerName(),
        e.getStateTransition()));
    return circuit;
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
      salle = proteger(tentativesCatalogue, circuitCatalogue,
          () -> catalogue.get()
              .uri("/salles/{id}", demande.salleId())
              .retrieve()
              .body(Salle.class));
    } catch (HttpClientErrorException.NotFound e) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
          "Salle inconnue : " + demande.salleId());
    }
    if (demande.participants() > salle.capacite()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
          "Capacité dépassée : " + salle.capacite() + " places au plus");
    }

    // 2. Appel synchrone aux disponibilites : bloquer le creneau.
    //    POST /occupations est idempotent (meme reservation_id) : une
    //    nouvelle tentative ne bloque jamais un second creneau.
    String id = UUID.randomUUID().toString();
    AtomicInteger envois = new AtomicInteger();
    try {
      proteger(tentativesDisponibilites, circuitDisponibilites, () -> {
        envois.incrementAndGet();
        return disponibilites.post()
            .uri("/occupations")
            .body(new Occupation(id, demande.salleId(), demande.debut(),
                demande.fin()))
            .retrieve()
            .toBodilessEntity();
      });
    } catch (HttpClientErrorException e) {
      // 409 (creneau pris) ou 422 (hors horaires) : on relaie le refus
      Erreur erreur = e.getResponseBodyAs(Erreur.class);
      throw new ResponseStatusException(e.getStatusCode(),
          erreur == null ? null : erreur.detail());
    } catch (RuntimeException e) {
      // Delai depasse, service en panne : une demande partie a peut-etre
      // bloque le creneau quand meme. On le libere (compensation).
      if (envois.get() > 0) {
        liberer(id);
      }
      throw e;
    }

    // 3. Enregistrer la reservation dans la base du service
    long minutes = Duration.between(demande.debut(), demande.fin())
        .toMinutes();
    BigDecimal montant = salle.prixHoraire()
        .multiply(BigDecimal.valueOf(minutes))
        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    Reservation reservation;
    try {
      reservation = base.sql("""
          INSERT INTO reservation (id, salle_id, utilisateur, debut, fin,
                                   participants, montant, statut)
          VALUES (?, ?, ?, ?, ?, ?, ?, 'CONFIRMEE')
          RETURNING *""")
          .params(id, demande.salleId(), utilisateur, demande.debut(),
              demande.fin(), demande.participants(), montant)
          .query(Reservation.class)
          .single();
    } catch (RuntimeException e) {
      liberer(id);  // pas de reservation : le creneau ne reste pas bloque
      throw e;
    }

    // 4. Publier la confirmation : le service notifications la traitera
    //    a son rythme, sans faire attendre l'utilisateur. Message
    //    persistant (valeur par defaut de Spring AMQP, ecrite ici pour
    //    qu'on la voie) : avec une file durable, il survit a un
    //    redemarrage de RabbitMQ. L'identifiant de la requete voyage
    //    dans un en-tete du message.
    rabbit.convertAndSend("reservations", "reservation.confirmee",
        reservation, message -> {
          message.getMessageProperties()
              .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
          message.getMessageProperties()
              .setHeader("X-Request-Id", MDC.get("request_id"));
          return message;
        });
    log.atInfo().addKeyValue("reservation_id", reservation.id())
        .log("Confirmation publiée pour la réservation {}", reservation.id());
    return reservation;
  }

  // Compensation : liberer le creneau d'une reservation abandonnee, en
  // arriere-plan pour ne pas faire attendre l'utilisateur davantage
  private void liberer(String id) {
    // Le MDC (request_id) est propre a chaque fil : on le recopie
    Map<String, String> contexte = MDC.getCopyOfContextMap();
    Thread.startVirtualThread(() -> {
      if (contexte != null) {
        MDC.setContextMap(contexte);
      }
      try {
        disponibilites.delete()
            .uri("/occupations/{id}", id)
            .retrieve()
            .toBodilessEntity();
        log.warn("Compensation : créneau de la réservation {} libéré", id);
      } catch (HttpClientErrorException.NotFound e) {
        log.info("Compensation : aucun créneau bloqué pour {}", id);
      } catch (RestClientException e) {
        log.error("Compensation impossible pour {}, créneau à vérifier : {}",
            id, e.getMessage());
      }
    });
  }

  // Service appele injoignable, trop lent (delai depasse) ou en erreur :
  // une 503 explicite plutot qu'une 500 generique
  @ExceptionHandler({ResourceAccessException.class,
      HttpServerErrorException.class})
  ProblemDetail serviceIndisponible(RestClientException e) {
    log.warn("Appel a un service en echec : {}", e.getMessage());
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
        "Service momentanément indisponible, réessayez plus tard");
  }

  // Coupe-circuit ouvert : refus immediat, sans appeler le service
  @ExceptionHandler(CallNotPermittedException.class)
  ResponseEntity<ProblemDetail> circuitOuvert(CallNotPermittedException e) {
    ProblemDetail probleme = ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE, "Service "
            + e.getCausingCircuitBreakerName()
            + " momentanément indisponible, réessayez dans 10 s");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Retry-After", "10")
        .body(probleme);
  }
}

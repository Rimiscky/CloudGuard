package coworking.reservations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

// Test de contrat cote consommateur : catalogue et disponibilites sont
// remplaces par un petit serveur HTTP (JDK) qui repond comme le disent
// leurs contrats. On verifie que reservations comprend ces reponses et
// les relaie correctement. Ni base, ni RabbitMQ : ils sont simules.
@SpringBootTest
@AutoConfigureMockMvc
class ReservationControllerTest {

  static HttpServer bouchon;
  // Appels recus pour LYO-03, salle dont le catalogue est "en panne"
  static final AtomicInteger appelsLyo03 = new AtomicInteger();

  @MockitoBean
  JdbcClient base;
  @MockitoBean
  RabbitTemplate rabbit;
  @Autowired
  MockMvc mvc;
  @Autowired
  CircuitBreakerRegistry circuits;

  @BeforeAll
  static void demarrerBouchon() throws IOException {
    bouchon = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    // Le contexte le plus long l'emporte : LYO-02 existe, le reste non
    bouchon.createContext("/salles/LYO-02", e -> repondre(e, 200, """
        {"id": "LYO-02", "capacite": 4, "prix_horaire": 25}"""));
    // LYO-03 : le catalogue repond 503, comme un service en difficulte
    bouchon.createContext("/salles/LYO-03", e -> {
      appelsLyo03.incrementAndGet();
      repondre(e, 503, """
          {"detail": "Service indisponible"}""");
    });
    bouchon.createContext("/salles/", e -> repondre(e, 404, """
        {"detail": "Salle inconnue"}"""));
    // Toute demande d'occupation tombe sur un creneau deja pris
    bouchon.createContext("/occupations", e -> repondre(e, 409, """
        {"detail": "Créneau déjà occupé"}"""));
    bouchon.start();
  }

  @AfterAll
  static void arreterBouchon() {
    bouchon.stop(0);
  }

  // Au demarrage, Spring a deja appele le faux RabbitTemplate (pour
  // construire un RabbitMessagingTemplate) : on repart d'une ardoise vierge.
  // Les coupe-circuits aussi : sinon les echecs d'un test pourraient en
  // ouvrir un pour le test suivant (l'ordre des tests n'est pas fixe).
  @BeforeEach
  void oublierLeDemarrage() {
    clearInvocations(rabbit);
    circuits.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    appelsLyo03.set(0);
  }

  // Les adresses des services pointent vers le bouchon
  @DynamicPropertySource
  static void adresses(DynamicPropertyRegistry registre) {
    String url = "http://localhost:" + bouchon.getAddress().getPort();
    registre.add("services.catalogue", () -> url);
    registre.add("services.disponibilites", () -> url);
  }

  static void repondre(HttpExchange echange, int statut, String json)
      throws IOException {
    byte[] corps = json.getBytes(StandardCharsets.UTF_8);
    echange.getResponseHeaders().set("Content-Type", "application/json");
    echange.sendResponseHeaders(statut, corps.length);
    echange.getResponseBody().write(corps);
    echange.close();
  }

  static String demande(String salle, int participants) {
    return """
        {"salle_id": "%s", "debut": "2026-12-01T10:00:00",
         "fin": "2026-12-01T11:00:00", "participants": %d}"""
        .formatted(salle, participants);
  }

  void reserver(String corps, int statut, String detail) throws Exception {
    mvc.perform(MockMvcRequestBuilders.post("/reservations")
            .header("X-Utilisateur", "alice")
            .contentType(MediaType.APPLICATION_JSON)
            .content(corps))
        .andExpect(MockMvcResultMatchers.status().is(statut))
        .andExpect(MockMvcResultMatchers.jsonPath("$.detail").value(detail));
  }

  @Test
  void demandeIncompleteRefusee() throws Exception {
    reserver("{\"salle_id\": \"LYO-02\"}", 400,
        "salle_id, debut, fin et participants sont obligatoires");
    verifyNoInteractions(rabbit);
  }

  @Test
  void salleInconnueDuCatalogue() throws Exception {
    reserver(demande("LYO-99", 2), 422, "Salle inconnue : LYO-99");
    verifyNoInteractions(rabbit);
  }

  @Test
  void capaciteLueDansLeCatalogue() throws Exception {
    reserver(demande("LYO-02", 6), 422,
        "Capacité dépassée : 4 places au plus");
    verifyNoInteractions(rabbit);
  }

  @Test
  void refusDesDisponibilitesRelaye() throws Exception {
    reserver(demande("LYO-02", 2), 409, "Créneau déjà occupé");
    verifyNoInteractions(base, rabbit);
  }

  // Resilience4j : 1 appel + 2 nouvelles tentatives, puis une 503 claire
  @Test
  void catalogueEnPanneApresTroisTentatives() throws Exception {
    reserver(demande("LYO-03", 2), 503,
        "Service momentanément indisponible, réessayez plus tard");
    assertEquals(3, appelsLyo03.get());
    verifyNoInteractions(base, rabbit);
  }
}

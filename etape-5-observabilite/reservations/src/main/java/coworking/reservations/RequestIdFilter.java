package coworking.reservations;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Lit X-Request-Id (pose par la passerelle) et le range dans le MDC :
// toutes les lignes de journal de cette requete porteront "request_id",
// et les appels sortants le reprendront (voir ReservationsApplication).
@Component
public class RequestIdFilter extends OncePerRequestFilter {

  private static final Logger JOURNAL = LoggerFactory.getLogger("acces");

  @Override
  protected void doFilterInternal(HttpServletRequest requete,
      HttpServletResponse reponse, FilterChain suite)
      throws ServletException, IOException {
    String recu = requete.getHeader("X-Request-Id");
    MDC.put("request_id", recu != null ? recu : UUID.randomUUID().toString());
    long debut = System.nanoTime();
    int statut = 500;  // si une exception remonte, Tomcat repondra 500
    try {
      suite.doFilter(requete, reponse);
      statut = reponse.getStatus();
    } finally {
      JOURNAL.atLevel(statut >= 500 ? Level.ERROR : Level.INFO)
          .addKeyValue("methode", requete.getMethod())
          .addKeyValue("chemin", requete.getRequestURI())
          .addKeyValue("statut", statut)
          .addKeyValue("duree_ms", (System.nanoTime() - debut) / 1_000_000)
          .log("{} {} {}", requete.getMethod(), requete.getRequestURI(),
              statut);
      MDC.remove("request_id");
    }
  }

  // Les controles de sante (toutes les 5 s) ne sont pas journalises
  @Override
  protected boolean shouldNotFilter(HttpServletRequest requete) {
    return requete.getRequestURI().startsWith("/actuator");
  }
}

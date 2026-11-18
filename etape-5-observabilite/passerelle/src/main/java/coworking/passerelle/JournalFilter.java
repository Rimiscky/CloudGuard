package coworking.passerelle;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

// Premier filtre de chaque requete, avant meme la verification du jeton :
// - X-Request-Id : repris du client s'il est raisonnable, cree sinon,
//   transmis au service et renvoye dans la reponse ;
// - une ligne de journal par requete, y compris les refus (401, 404...).
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JournalFilter implements WebFilter {

  private static final Logger JOURNAL = LoggerFactory.getLogger("acces");
  // 64 caracteres au plus : lettres, chiffres, point, tiret, souligne
  private static final Pattern ID_ACCEPTE = Pattern.compile("[\\w.-]{1,64}");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String chemin = exchange.getRequest().getPath().value();
    if (chemin.startsWith("/actuator")) {  // controles de sante : silence
      return chain.filter(exchange);
    }
    String recu = exchange.getRequest().getHeaders()
        .getFirst("X-Request-Id");
    String requestId = recu != null && ID_ACCEPTE.matcher(recu).matches()
        ? recu : UUID.randomUUID().toString();
    long debut = System.nanoTime();

    var reponse = exchange.getResponse();
    reponse.getHeaders().set("X-Request-Id", requestId);
    // Juste avant l'envoi de la reponse : son code est alors definitif
    reponse.beforeCommit(() -> {
      String methode = exchange.getRequest().getMethod().name();
      HttpStatusCode code = reponse.getStatusCode();
      int statut = code == null ? 200 : code.value();
      JOURNAL.atLevel(statut >= 500 ? Level.ERROR : Level.INFO)
          .addKeyValue("request_id", requestId)
          .addKeyValue("methode", methode)
          .addKeyValue("chemin", chemin)
          .addKeyValue("statut", statut)
          .addKeyValue("duree_ms", (System.nanoTime() - debut) / 1_000_000)
          .log("{} {} {}", methode, chemin, statut);
      return Mono.empty();
    });

    return chain.filter(exchange.mutate()
        .request(requete -> requete.headers(entetes ->
            entetes.set("X-Request-Id", requestId)))
        .build());
  }
}

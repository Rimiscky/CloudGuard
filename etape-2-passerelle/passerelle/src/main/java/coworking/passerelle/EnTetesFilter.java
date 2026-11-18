package coworking.passerelle;

import java.security.Principal;
import java.util.UUID;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

// Filtre applique a toutes les routes, avant les autres filtres :
// - X-Request-Id : identifiant de la requete, cree s'il manque ;
// - X-Utilisateur : sujet du jeton verifie, transmis aux services.
@Component
public class EnTetesFilter implements GlobalFilter, Ordered {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange,
      GatewayFilterChain chain) {
    String recu = exchange.getRequest().getHeaders()
        .getFirst("X-Request-Id");
    String requestId = recu != null ? recu : UUID.randomUUID().toString();
    exchange.getResponse().getHeaders().set("X-Request-Id", requestId);

    return exchange.getPrincipal()
        .map(Principal::getName)
        .defaultIfEmpty("")
        .flatMap(utilisateur -> chain.filter(exchange.mutate()
            .request(requete -> requete.headers(entetes -> {
              entetes.set("X-Request-Id", requestId);
              // jamais de confiance dans un X-Utilisateur venu du client
              entetes.remove("X-Utilisateur");
              if (!utilisateur.isEmpty()) {
                entetes.set("X-Utilisateur", utilisateur);
              }
            }))
            .build()));
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}

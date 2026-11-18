package coworking.passerelle;

import java.security.Principal;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

// Filtre applique a toutes les routes, avant les autres filtres :
// X-Utilisateur, sujet du jeton verifie, transmis aux services.
// (X-Request-Id est pose plus tot, par JournalFilter.)
@Component
public class EnTetesFilter implements GlobalFilter, Ordered {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange,
      GatewayFilterChain chain) {
    return exchange.getPrincipal()
        .map(Principal::getName)
        .defaultIfEmpty("")
        .flatMap(utilisateur -> chain.filter(exchange.mutate()
            .request(requete -> requete.headers(entetes -> {
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

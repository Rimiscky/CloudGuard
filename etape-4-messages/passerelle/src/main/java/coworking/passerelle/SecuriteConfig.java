package coworking.passerelle;

import java.util.Objects;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class SecuriteConfig {

  @Bean
  SecurityWebFilterChain securite(ServerHttpSecurity http) {
    return http
        // API sans cookie de session : pas de jeton CSRF
        .csrf(csrf -> csrf.disable())
        .authorizeExchange(acces -> acces
            .pathMatchers("/actuator/health").permitAll()
            // tout le reste exige un jeton valide
            .anyExchange().authenticated())
        // jeton JWT verifie avec la cle publique (voir application.yml)
        .oauth2ResourceServer(serveur -> serveur
            .jwt(Customizer.withDefaults()))
        .build();
  }

  // Controles ajoutes a ceux de Spring (signature, dates) : Spring Boot
  // reprend tout bean OAuth2TokenValidator<Jwt> dans le decodeur
  @Bean
  OAuth2TokenValidator<Jwt> emetteurAttendu() {
    return new JwtIssuerValidator("coworking-demo");
  }

  @Bean
  OAuth2TokenValidator<Jwt> expirationObligatoire() {
    return new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull);
  }

  @Bean
  OAuth2TokenValidator<Jwt> sujetObligatoire() {
    return new JwtClaimValidator<Object>(JwtClaimNames.SUB, Objects::nonNull);
  }
}

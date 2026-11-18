package coworking.reservations;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
public class ReservationsApplication {

  public static void main(String[] args) {
    SpringApplication.run(ReservationsApplication.class, args);
  }

  // Echange RabbitMQ qui recoit les confirmations de reservation
  @Bean
  TopicExchange echangeReservations() {
    return new TopicExchange("reservations");
  }

  // File durable du service notifications, declaree aussi ici : elle
  // existe avant la premiere publication, meme si notifications n'a
  // jamais demarre (sans file reliee, RabbitMQ jetterait le message)
  @Bean
  Queue fileNotifications() {
    return new Queue("notifications.reservations", true);
  }

  @Bean
  Binding liaisonNotifications(Queue fileNotifications,
      TopicExchange echangeReservations) {
    return BindingBuilder.bind(fileNotifications).to(echangeReservations)
        .with("reservation.confirmee");
  }

  // Messages en JSON, avec les memes noms de champs que l'API
  @Bean
  MessageConverter convertisseurJson(JsonMapper jsonMapper) {
    return new JacksonJsonMessageConverter(jsonMapper);
  }
}

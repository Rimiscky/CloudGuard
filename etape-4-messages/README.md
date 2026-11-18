# Étape 4 : la confirmation passe par RabbitMQ

État du système à la fin du sous-chapitre 4.2 : l'étape 3, plus un échange asynchrone. Une fois la réservation enregistrée, reservations publie un message `reservation.confirmee` dans RabbitMQ ; le service notifications le consomme et simule l'envoi d'une confirmation. L'utilisateur n'attend pas l'envoi.

## Architecture

```
                 poste : http://localhost:8080 (jeton JWT)
                                   |
          +------------------------v-------------------+     +-------+
          |                 passerelle                 |---->| redis |
          |            Spring Cloud Gateway            |     +-------+
          +---+-----------------+-----------------+----+
              | (1)             | (2)             | (3)
              v                 v                 v
     +-----------------+ +----------------+ +-------------------+
     |    catalogue    | | disponibilites | |   reservations    |
     |  Node, Express  | | Python, FastAPI| | Java, Spring Boot |
     +--------+--------+ +-------+--------+ +--+-------------+--+
              |                  |             |             |
     +--------v--------+ +-------v--------+ +--v---------+ +-v--------+
     |   PostgreSQL    | |     SQLite     | | PostgreSQL | | RabbitMQ |
     +-----------------+ +----------------+ +------------+ +----+-----+
                                                                |
                                                                v
                                                        +---------------+
                                                        | notifications |
                                                        |    Python     |
                                                        +---------------+
```

Les appels synchrones de étape 3 (reservations vers catalogue et disponibilités, catalogue vers disponibilités) ne sont pas dessinés.

| Conteneur ajouté | Rôle | Image de base | Port |
|------------------|------|---------------|------|
| `rabbitmq` | file de messages | `rabbitmq:4.4.0-management-alpine` | 5672 (interne) ; interface sur 127.0.0.1:15672 |
| `notifications` | consommateur des confirmations | `python:3.14.8-slim-trixie` | aucun |

## Ce qui est garanti, et comment

| Garantie | Où |
|----------|----|
| Le message n'est jamais publié dans le vide : la file durable `notifications.reservations` et sa liaison à l'échange `reservations` (type topic, clé `reservation.confirmee`) sont déclarées par le producteur (beans `Queue` et `Binding` de `ReservationsApplication`, déclarés par Spring AMQP à l'ouverture de la connexion) **et** par le consommateur. Les deux démarrent dans n'importe quel ordre. | `ReservationsApplication.java`, `notifications.py` |
| File durable, message persistant (`delivery_mode` 2) : il attend un consommateur arrêté et survit au redémarrage de RabbitMQ. | `ReservationController.java` (étape 4), `notifications.py` |
| Accusé de réception manuel, envoyé après le traitement ; un seul message non acquitté à la fois (`prefetch_count=1`). Un consommateur arrêté brutalement avant l'accusé reçoit de nouveau le message, marqué « (message redistribué) » (livraison « au moins une fois »). | `notifications.py` |
| Consommateur idempotent : chaque réservation confirmée est notée (son identifiant) dans un fichier SQLite, `/donnees/notifications.db`, après l'envoi et avant l'accusé de réception. Un message pour une réservation déjà notée (redistribué, ou publié deux fois) est acquitté sans renvoyer la confirmation : « Réservation xxxxxxxx déjà confirmée, message ignoré ». La mémoire est sur le volume `notifications-donnees` : elle survit au redémarrage du conteneur (`restart`, `kill` puis `start`) et à sa recréation (`up -d` après un changement) ; `docker compose down -v` l'efface. | `notifications.py`, `notifications/Dockerfile` (`/donnees`), `docker-compose.yml` (volume) |
| Le consommateur, qui ne se reconnecte pas seul, s'arrête quand RabbitMQ s'arrête ; Docker le relance (`restart: on-failure`) jusqu'au retour du courtier. | `docker-compose.yml` |
| Contrôle de santé de RabbitMQ léger : `nc -z localhost 5672` (le port AMQP accepte une connexion). `rabbitmq-diagnostics ping` répond avant l'ouverture de ce port ; `check_port_connectivity` lance une machine Erlang à chaque contrôle (1 à 1,3 s et des pointes à 270 % de processeur, contre 0,05 s et moins de 5 % pour `nc`), et, lancé dès la première seconde (`start_interval: 1s`), crée le cookie Erlang au nom de root : RabbitMQ s'arrête (`eacces`). | `docker-compose.yml` |

## Démarrer, observer

```bash
docker compose up -d --build --wait
TOKEN=$(node outils/jeton.mjs alice)
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"salle_id": "LYO-01", "debut": "2026-11-16T14:00:00",
       "fin": "2026-11-16T16:00:00", "participants": 6}' \
  http://localhost:8080/api/reservations
docker compose logs notifications        # la confirmation consommee
docker compose exec rabbitmq rabbitmqctl -q list_queues name durable \
  messages_ready messages_unacknowledged consumers
```

Interface d'administration de RabbitMQ : http://localhost:15672 (utilisateur `coworking`, mot de passe `rabbitmq-mdp`).

Arrêt brutal entre l'envoi et l'accusé de réception, reproductible :

```bash
PAUSE_AVANT_ACK=8 docker compose up -d notifications  # 8 s avant l'accuse
curl ... http://localhost:8080/api/reservations        # une reservation
sleep 2; docker compose kill notifications            # SIGKILL, en pause
docker compose start notifications                     # meme conteneur
docker compose logs notifications | tail -2
# Confirmation envoyée à ... réservation 1a2b3c4d
# Réservation 1a2b3c4d déjà confirmée, message ignoré (message redistribué)
docker compose up -d notifications                     # retour sans pause
```

Docker ne relance pas un conteneur tué par `docker compose kill`, malgré `restart: on-failure` : `start` est nécessaire.

## Limites connues

- Si RabbitMQ est arrêté au moment de publier, la réservation est déjà enregistrée et le créneau bloqué, mais la confirmation n'est jamais publiée et l'appel échoue (pas de boîte d'envoi transactionnelle, *transactional outbox*).
- La marque « déjà confirmée » est écrite après l'envoi : un arrêt entre l'envoi et cette écriture renverrait encore la confirmation (fenêtre réduite au temps d'une écriture SQLite, pas fermée ; non provoquée). L'envoi réel (courriel) ne peut pas entrer dans la même transaction que le fichier SQLite.
- La mémoire grandit sans fin (aucune purge) et n'est partagée entre plusieurs consommateurs que s'ils tournent sur le même hôte (même volume).

## Arborescence

Celle de étape 3, plus :

```
notifications/            consommateur RabbitMQ : notifications.py (memoire
                          des confirmations sur le volume
                          notifications-donnees, /donnees)
```

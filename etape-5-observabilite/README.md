# Étape 5 : journaux corrélés et tests (état du TP final)

État du système à la fin du chapitre 5. Tout ce que construisent les chapitres précédents (trois services, passerelle, résilience, file de messages), plus le suivi d'une requête d'un bout à l'autre : un identifiant `X-Request-Id`, créé ou repris par la passerelle, traverse les trois services et le message RabbitMQ, et chaque service écrit des journaux JSON qui le portent. Des tests, un par service et un de bout en bout, se lancent dans des conteneurs.

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

Routes : (1) `/api/espaces`, `/api/salles`, `/api/equipements` ; (2) `/api/disponibilites` ; (3) `/api/reservations`. Appels synchrones entre services (non dessinés) : reservations vers le catalogue (salle, capacité, prix) puis vers les disponibilités (blocage du créneau, compensation) ; le catalogue vers les disponibilités (recherche de salles libres).

| Conteneur | Rôle | Image de base | Port |
|-----------|------|---------------|------|
| `passerelle` | routage, jeton, limitation de débit, `X-Request-Id` | `eclipse-temurin:25.0.4.1_1-jre-alpine-3.24` | 8080, publié sur 127.0.0.1:8080 |
| `redis` | compteurs de la limitation de débit | `redis:8.10.2-alpine3.23` | 6379 |
| `catalogue` | espaces, salles, équipements | `node:26.12.1-alpine3.24` | 3000 |
| `catalogue-db` | base du catalogue | `postgres:18.6-alpine3.24` | 5432 |
| `disponibilites` | créneaux libres et occupés (fichier SQLite) | `python:3.14.8-slim-trixie` | 8000 |
| `reservations` | création et lecture des réservations | `eclipse-temurin:25.0.4.1_1-jre-alpine-3.24` | 8080 |
| `reservations-db` | base des réservations | `postgres:18.6-alpine3.24` | 5432 |
| `rabbitmq` | file de messages | `rabbitmq:4.4.0-management-alpine` | 5672 ; interface sur 127.0.0.1:15672 |
| `notifications` | consommateur des confirmations | `python:3.14.8-slim-trixie` | aucun |

## Démarrer, utiliser, arrêter

Prérequis : Docker avec Compose v2 (essayé avec Docker 29.8 sous Windows 11), Node.js 20 ou plus sur le poste pour `outils/jeton.mjs` et `outils/parcours.mjs` (sans dépendance).

```bash
docker compose up -d --build --wait     # premiere fois : 3 a 6 minutes
docker compose ps                        # 9 conteneurs, 8 "healthy"
TOKEN=$(node outils/jeton.mjs alice)
curl -i -H "Authorization: Bearer $TOKEN" -H "X-Request-Id: tuto-0001" \
  -H "Content-Type: application/json" \
  -d '{"salle_id": "LYO-01", "debut": "2026-11-16T14:00:00",
       "fin": "2026-11-16T16:00:00", "participants": 6}' \
  http://localhost:8080/api/reservations
```

Arrêter : `docker compose down` (garde les données) ou `docker compose down -v` (retour aux données de démonstration).

## Suivre une requête dans les journaux

Chaque service écrit une ligne JSON par requête (et par événement notable : nouvelle tentative, coupe-circuit, compensation, publication, confirmation), avec les mêmes champs : `@timestamp`, `log.level`, `service.name`, `message`, `request_id`, et pour les requêtes `methode`, `chemin`, `statut`, `duree_ms`. Les contrôles de santé ne sont pas journalisés.

```bash
docker compose logs --no-log-prefix | grep -E '"request_id":"tuto-0001"' \
  | node outils/parcours.mjs
```

`parcours.mjs` remet les lignes dans l'ordre chronologique (`docker compose logs` ne le garantit pas d'un conteneur à l'autre) et les résume : heure, service, durée, message.

Comment l'identifiant voyage :

- **passerelle** (`JournalFilter`, premier filtre, avant la vérification du jeton) : reprend le `X-Request-Id` du client s'il fait au plus 64 caractères parmi lettres, chiffres, `.`, `-`, `_`, en crée un (UUID) sinon ; le transmet au service, le renvoie au client, journalise aussi les refus (401, 404, 429) ;
- **reservations** (`RequestIdFilter`) : le range dans le MDC, d'où il passe dans chaque ligne, dans les appels sortants (`RestClientCustomizer` de `ReservationsApplication`), dans la compensation (MDC recopié dans son fil) et dans un en-tête du message RabbitMQ ;
- **catalogue** : le lit (ou en crée un) et le transmet dans ses appels à disponibilites ;
- **disponibilites** : le lit (ou en crée un) dans un intergiciel FastAPI ; Uvicorn tourne avec `--no-access-log` ;
- **notifications** : le lit dans les en-têtes du message ; la ligne d'un doublon ignoré (« déjà confirmée, message ignoré ») le porte aussi.

Ce n'est pas du traçage distribué (pas de lien parent-enfant entre appels, pas d'échantillonnage) ; la norme du domaine est W3C Trace Context, mise en œuvre par OpenTelemetry.

## Tests

Cinq services Compose dans le profil `tests` : `docker compose up` ne les démarre pas, `docker compose run --rm <service>` les lance (et démarre ce dont ils dépendent). Rien à installer sur le poste.

```bash
docker compose --profile tests build
docker compose run --rm tests-catalogue       # 6 tests (node --test)
docker compose run --rm tests-disponibilites  # 7 tests (pytest)
docker compose run --rm tests-notifications   # 2 tests (pytest)
docker compose run --rm tests-reservations    # 5 tests (JUnit, MockMvc)
docker compose run --rm tests-bout-en-bout    # 3 tests, par la passerelle
```

| Service de test | Ce qu'il exerce |
|-----------------|-----------------|
| `tests-catalogue` | le vrai catalogue et sa vraie base : filtres, erreurs 400 et 404, et le contrat attendu par reservations (`capacite` entière, `prix_horaire` nombre) |
| `tests-disponibilites` | l'application FastAPI en mémoire sur un fichier SQLite jetable : planning, chevauchement refusé, horaires, libération, rejeu idempotent, demande arrivée après son annulation ; réponses comparées aux schémas du contrat `openapi.yaml` |
| `tests-notifications` | le consommateur sans RabbitMQ : `recevoir()` appelée deux fois avec le même message (canal et livraison simulés, fichier SQLite jetable) ; une seule confirmation, deux accusés de réception, et la mémoire retrouvée après rechargement du module (comme après un redémarrage). `notifications.py` ne se connecte à RabbitMQ que lancé comme programme (`if __name__ == "__main__"`), pas quand un test l'importe. |
| `tests-reservations` | test de contrat côté consommateur : catalogue et disponibilités remplacés par un petit serveur HTTP qui répond comme leurs contrats, base et RabbitMQ simulés ; refus relayés, et trois tentatives avant une `503` quand le catalogue est en panne |
| `tests-bout-en-bout` | un parcours d'utilisateur à travers la passerelle, jeton compris : 401 sans jeton, chercher une salle, lire son premier créneau libre, réserver, retrouver la réservation ; `X-Utilisateur` forgé ignoré. Il lit l'état avant d'agir : il se rejoue sans remise à zéro. Son `X-Request-Id` (`bout-en-bout-<horodatage>`) est affiché pour suivre son parcours. |

## Pannes simulées (pour le TP)

```bash
LATENCE_S=3 PANNE_NB=3 docker compose up -d --wait disponibilites
PANNE_HTTP=503 docker compose up -d --wait disponibilites
PAUSE_AVANT_ACK=8 docker compose up -d notifications
docker compose up -d --wait disponibilites notifications   # retour normal
```

## Contrats

Un contrat OpenAPI 3.1 par service, écrit à la main (version 1.1.0) : `catalogue/openapi.yaml`, `disponibilites/openapi.yaml`, `reservations/openapi.yaml`. FastAPI publie aussi le contrat qu'il déduit du code (`/openapi.json` et `/docs` du service disponibilites, non publiés par la passerelle).

## Ce qui est volontairement laissé en l'état

- Pas de boîte d'envoi transactionnelle : RabbitMQ arrêté au moment de publier fait échouer l'appel alors que la réservation est enregistrée.
- Consommateur idempotent (mémoire SQLite sur le volume `notifications-donnees`), mais la marque est écrite après l'envoi : un arrêt entre les deux renverrait encore la confirmation ; la mémoire n'est jamais purgée.
- Les services croient l'en-tête `X-Utilisateur` sans revérifier le jeton : seul le réseau Docker les protège.
- Mots de passe en clair dans `docker-compose.yml` et clé privée de démonstration dans `cles/`, à régénérer (`bash outils/generer-cles.sh`) et à ne jamais reprendre ailleurs.
- Le client HTTP du JDK (utilisé par reservations) propose une montée en HTTP/2 que Uvicorn ignore : pour chaque appel, deux avertissements non JSON dans le journal de disponibilites (`No supported WebSocket library detected...` et `Unsupported upgrade request.`), sans effet sur les réponses.

## Arborescence

```
docker-compose.yml        les 9 conteneurs, 5 services de test (profil tests)
cles/                     paire RSA de demonstration (prive.pem, public.pem)
outils/jeton.mjs          emet un jeton JWT RS256 signe avec cles/prive.pem
outils/parcours.mjs       remet dans l'ordre les lignes d'une requete
outils/generer-cles.sh    regenere la paire de cles
passerelle/               Spring Cloud Gateway : application.yml,
                          SecuriteConfig, EnTetesFilter, JournalFilter
catalogue/                Express : src/index.js, db/init.sql, openapi.yaml,
                          test/catalogue.test.mjs
disponibilites/           FastAPI : app/main.py, app/init.sql, openapi.yaml,
                          tests/, Dockerfile.tests, requirements-tests.txt
reservations/             Spring Boot : ReservationController, Reservation,
                          RequestIdFilter, db/init.sql, openapi.yaml,
                          src/test/.../ReservationControllerTest.java
notifications/            consommateur RabbitMQ : notifications.py (memoire
                          des confirmations sur le volume
                          notifications-donnees), tests/,
                          Dockerfile.tests, requirements-tests.txt
tests/bout-en-bout.test.mjs   parcours complet a travers la passerelle
```

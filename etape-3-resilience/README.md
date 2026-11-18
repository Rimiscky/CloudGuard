# Étape 3 : délais, nouvelles tentatives, coupe-circuits

État du système à la fin du sous-chapitre 4.1 : l'étape 2, rendu résistant aux pannes des appels synchrones. Délais explicites partout, nouvelles tentatives et coupe-circuit dans reservations (Resilience4j), réponse dégradée dans le catalogue quand les disponibilités ne répondent pas (opossum), opération d'occupation idempotente et compensation des réservations abandonnées.

## Architecture

Mêmes conteneurs qu'en étape 2 (passerelle, Redis, trois services et leurs stockages). Un appel de plus : le catalogue lit désormais les disponibilités pour la recherche de salles libres. Reservations appelle toujours le catalogue (salle, capacité, prix) puis les disponibilités (blocage du créneau).

```
               poste : http://localhost:8080 (jeton JWT)
                                 |
      +--------------------------v---------------------------+    +-------+
      |                       passerelle                     |--->| redis |
      |       delais : connexion 2 s, reponse 10 s           |    +-------+
      +----+------------------+-----------------------+------+
           |                  |                       |
  +--------v--------+   +-----v----------+   +--------v----------+
  |    catalogue    |   | disponibilites |   |   reservations    |
  | opossum, p-retry|-->|  idempotent    |<--|   Resilience4j    |
  +---+---------^---+   +-----+----------+   +---+-----------+---+
      |         |             |                  |           |
      |         +-------------|------------------+           |
      |      (salle, capacite, prix)                         |
  +---v--------+        +-----v------+                 +-----v------+
  | PostgreSQL |        |   SQLite   |                 | PostgreSQL |
  +------------+        +------------+                 +------------+
```

## Ce qui change par rapport à étape 2

| Où | Quoi |
|----|------|
| reservations, `application.yml` | `spring.http.clients.connect-timeout: 1s` et `read-timeout: 2s` (sans eux, le client HTTP du JDK attend sans limite) ; réglages Resilience4j : 3 tentatives vers chaque service (500 ms puis 1 s d'attente vers le catalogue, 500 ms vers les disponibilités), coupe-circuit sur les 10 derniers appels, verdict dès 5 appels, ouvert à 50 % d'échecs, 10 s ouvert, 2 appels d'essai ; `/actuator/circuitbreakers` exposé (réseau Docker seulement) |
| reservations, `ReservationController` | chaque appel enveloppé : tentatives(coupe-circuit(appel)) ; service injoignable, lent ou en erreur : `503` au format RFC 9457 ; coupe-circuit ouvert : `503` immédiat avec `Retry-After: 10` ; compensation : si une demande d'occupation est partie et que la réservation échoue, `DELETE /occupations/{id}` en arrière-plan |
| passerelle, `application.yml` | `connect-timeout: 2000` et `response-timeout: 10s` sous `spring.cloud.gateway.server.webflux.httpclient` (sous l'ancien préfixe, ignorés sans avertissement) : au-delà, `504` |
| disponibilites, `main.py` | `POST /occupations` idempotent (même `reservation_id`, même créneau : `200`, rien de plus) ; `DELETE` note l'annulation, et une demande d'occupation arrivée après son annulation est refusée (`409 Réservation annulée`) ; pannes simulées par variables d'environnement (`LATENCE_S`, `PANNE_HTTP`, `PANNE_NB`) |
| catalogue, `index.js` | `GET /salles?...&date=&debut=&fin=` ajoute `libre` à chaque salle, lu dans disponibilites (délai de 1 s par appel, 2 nouvelles tentatives par p-retry, coupe-circuit opossum) ; si une lecture échoue : réponse partielle, `libre: null` et `X-Disponibilites: inconnues` ; une ligne de journal par requête |
| contrats | version 1.1.0 : paramètres `date`, `debut`, `fin` et champ `libre` du catalogue, réponse `200` de `POST /occupations`, réponse `503` de `POST /reservations` |

## Démarrer, provoquer des pannes

```bash
docker compose up -d --build --wait
TOKEN=$(node outils/jeton.mjs alice)
Q="ville=Lyon&date=2026-11-16&debut=14:00&fin=16:00"
curl -i -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/salles?$Q"
```

Pannes de disponibilites, sans toucher au code (le conteneur est recréé, les données restent dans le volume) :

```bash
LATENCE_S=3 PANNE_NB=1 docker compose up -d --wait disponibilites
                                   # une reponse en 3 s, puis normal
LATENCE_S=12 docker compose up -d --wait disponibilites   # toujours lent
PANNE_HTTP=503 docker compose up -d --wait disponibilites # toujours 503
docker compose up -d --wait disponibilites                # retour normal
docker compose stop disponibilites       # service arrete
docker compose pause disponibilites      # processus geles (puis unpause)
```

État des coupe-circuits de reservations (le port n'est pas publié, on passe par le conteneur) :

```bash
docker compose exec reservations \
  wget -qO- http://localhost:8080/actuator/circuitbreakers
```

`COUPE_CIRCUIT=0 docker compose up -d --wait catalogue` désactive le coupe-circuit du catalogue, pour comparer (jamais en production).

## Ce qui reste à faire (états suivants)

La confirmation de réservation n'est envoyée à personne (pas de file de messages) ; les journaux ne permettent pas de suivre une requête d'un service à l'autre. Limite connue de la compensation : si disponibilites est injoignable au moment de l'annulation après avoir bloqué le créneau (un arrêt, pas un gel), l'annulation échoue ; reservations l'écrit dans son journal (« Compensation impossible ... créneau à vérifier »), sans reprise automatique (déduit du code, cas non joué ; le cas du service gelé, lui, est joué et ne laisse rien).

## Arborescence

Celle de étape 2 ; le code nouveau est dans les fichiers cités plus haut.

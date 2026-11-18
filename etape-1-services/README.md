# Étape 1 : trois services appelés directement

État du système à la fin du chapitre 2. Un réseau d'espaces de travail partagés loue des salles de réunion à l'heure ; trois services écrits dans trois écosystèmes se partagent le travail, chacun avec son propre stockage, assemblés par Docker Compose. Il n'y a encore ni passerelle, ni jeton, ni file de messages : chaque service est publié sur le poste et s'appelle directement.

## Architecture

```
          localhost:3000        localhost:8000          localhost:8081
                |                     |                       |
        +-------v-------+    +--------v--------+    +---------v---------+
        |   catalogue   |    | disponibilites  |    |   reservations    |
        | Node, Express |    | Python, FastAPI |    | Java, Spring Boot |
        +--+---------^--+    +--+----------^---+    +--+------+------+--+
           |         |          |          |           |      |      |
           |         |          |          +-----------|------+      |
           |         |          |            (2) POST  |             |
           |         +----------|----------------------+             |
           |                    |   (1) GET /salles/{id}             |
     +-----v------+       +-----v------+                       +-----v------+
     | PostgreSQL |       |   SQLite   |                       | PostgreSQL |
     +------------+       +------------+                       +------------+
```

Pour créer une réservation, le service reservations appelle en synchrone (1) le catalogue (la salle existe-t-elle, combien de places, quel prix), puis (2) les disponibilités (bloquer le créneau), et enregistre la réservation dans sa base.

| Conteneur | Rôle | Image de base | Port |
|-----------|------|---------------|------|
| `catalogue` | espaces, salles, équipements | `node:26.12.1-alpine3.24` | 3000, publié sur 127.0.0.1:3000 |
| `catalogue-db` | base du catalogue | `postgres:18.6-alpine3.24` | 5432 (interne) |
| `disponibilites` | créneaux libres et occupés (fichier SQLite) | `python:3.14.8-slim-trixie` | 8000, publié sur 127.0.0.1:8000 |
| `reservations` | création et lecture des réservations | `eclipse-temurin:25.0.4.1_1-jre-alpine-3.24` | 8080, publié sur 127.0.0.1:8081 |
| `reservations-db` | base des réservations | `postgres:18.6-alpine3.24` | 5432 (interne) |

Les trois services redémarrent seuls s'ils s'arrêtent sur une erreur (`restart: on-failure`) ; un arrêt demandé (`docker compose stop`) est respecté.

## Démarrer, utiliser, arrêter

Depuis ce dossier :

```bash
docker compose up -d --build --wait     # premiere fois : 2 a 5 minutes
docker compose ps                        # 5 conteneurs "healthy"
```

Appels directs :

```bash
curl http://localhost:3000/espaces
curl "http://localhost:3000/salles?ville=Lyon&capacite_min=6"
curl "http://localhost:8000/disponibilites/LYO-01?date=2026-11-16"
curl -H "X-Utilisateur: alice" http://localhost:8081/reservations
curl -H "X-Utilisateur: alice" -H "Content-Type: application/json" \
  -d '{"salle_id": "LYO-01", "debut": "2026-11-16T14:00:00",
       "fin": "2026-11-16T16:00:00", "participants": 6}' \
  http://localhost:8081/reservations
```

Sans passerelle, c'est le client qui pose l'en-tête `X-Utilisateur` : n'importe qui peut se faire passer pour n'importe qui. C'est ce que corrige l'état suivant.

Arrêter :

```bash
docker compose down        # garde les donnees (volumes)
docker compose down -v     # efface aussi les volumes : retour aux donnees
                           # de demonstration au prochain demarrage
```

Si un port est déjà pris sur le poste, le changer au lancement : `PORT_CATALOGUE=13000 PORT_DISPONIBILITES=18000 PORT_RESERVATIONS=18081 docker compose up -d --wait`.

## Données de démonstration

Figées dans `catalogue/db/init.sql`, `disponibilites/app/init.sql` et `reservations/db/init.sql`, chargées au premier démarrage (volume vide) : 4 espaces (Lyon Part-Dieu, Nantes Île de Nantes, Lille Euralille, Bordeaux Chartrons), 13 salles de 2 à 20 places et de 15 à 110 euros de l'heure, 6 équipements, 10 réservations confirmées du 16 au 20 novembre 2026 pour six utilisateurs (`alice`, `bruno`, `chloe`, `david`, `emma`, `farid`). Ouverture de 8 h à 20 h tous les jours, heures locales sans fuseau. Aucune règle ne dépend de la date du jour.

## Contrats

Un contrat OpenAPI 3.1 par service, écrit à la main : `catalogue/openapi.yaml`, `disponibilites/openapi.yaml`, `reservations/openapi.yaml`. À ce stade, ils décrivent les services appelés directement (aucune authentification). FastAPI publie aussi le contrat qu'il déduit du code : http://localhost:8000/docs.

## Choix de robustesse déjà en place

- Le pool PostgreSQL du catalogue a un écouteur `error` : si la base s'arrête, le service survit et répond de nouveau quand elle revient.
- Le catalogue tourne avec `NODE_ENV=production` (aucune pile d'appels renvoyée au client en cas d'erreur) et n'envoie pas l'en-tête `X-Powered-By`.
- Les bases PostgreSQL ont un `start_period` : une initialisation lente ne fait pas échouer `up --wait`.
- `participants` est un `Integer` dans la demande reçue par reservations : un champ absent donne le message explicite « salle_id, debut, fin et participants sont obligatoires ».

## Arborescence

```
docker-compose.yml        les 5 conteneurs, volumes, ports, controles de sante
catalogue/                Express : src/index.js, db/init.sql, openapi.yaml
disponibilites/           FastAPI : app/main.py, app/init.sql, openapi.yaml
reservations/             Spring Boot : ReservationController, Reservation,
                          db/init.sql, openapi.yaml
```

# Étape 2 : la passerelle, seule porte d'entrée

État du système à la fin du chapitre 3 : les trois services de l'étape 1, derrière une passerelle d'API (Spring Cloud Gateway) qui route les requêtes, vérifie le jeton JWT et limite le débit. Les services ne sont plus publiés sur le poste : seule la passerelle l'est.

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
     +--------+--------+ +-------+--------+ +---------+---------+
              |                  |                    |
     +--------v--------+ +-------v--------+     +-----v------+
     |   PostgreSQL    | |     SQLite     |     | PostgreSQL |
     +-----------------+ +----------------+     +------------+
```

Routes : (1) `/api/espaces`, `/api/salles`, `/api/equipements` ; (2) `/api/disponibilites` ; (3) `/api/reservations`. Comme en étape 1, reservations appelle directement le catalogue puis les disponibilités sur le réseau de Docker Compose.

| Conteneur | Rôle | Image de base | Port |
|-----------|------|---------------|------|
| `passerelle` | routage, vérification du jeton, limitation de débit | `eclipse-temurin:25.0.4.1_1-jre-alpine-3.24` | 8080, publié sur 127.0.0.1:8080 |
| `redis` | compteurs de la limitation de débit | `redis:8.10.2-alpine3.23` | 6379 (interne) |
| `catalogue`, `catalogue-db`, `disponibilites`, `reservations`, `reservations-db` | comme en étape 1 | | ports internes seulement |

## Démarrer, utiliser, arrêter

Depuis ce dossier :

```bash
docker compose up -d --build --wait     # premiere fois : 3 a 6 minutes
docker compose ps                        # 7 conteneurs "healthy"
```

Obtenir un jeton (valable une heure, Node.js 20 ou plus sur le poste) et appeler l'API à travers la passerelle :

```bash
TOKEN=$(node outils/jeton.mjs alice)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/espaces
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/disponibilites/LYO-01?date=2026-11-16"
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"salle_id": "LYO-01", "debut": "2026-11-16T14:00:00",
       "fin": "2026-11-16T16:00:00", "participants": 6}' \
  http://localhost:8080/api/reservations
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reservations
```

Un jeton déjà expiré, pour les essais : `node outils/jeton.mjs alice -120` (la passerelle tolère 60 secondes d'écart d'horloge).

Arrêter : `docker compose down` (garde les données) ou `docker compose down -v` (retour aux données de démonstration). Port déjà pris : `PORT_PASSERELLE=18080 docker compose up -d --wait`.

## Routes publiées par la passerelle

Toutes exigent un jeton valide ; la passerelle retire le préfixe `/api` avant de transmettre.

| Chemin public | Service | Chemin reçu par le service |
|---------------|---------|----------------------------|
| `GET /api/espaces` | catalogue | `/espaces` |
| `GET /api/equipements` | catalogue | `/equipements` |
| `GET /api/salles?ville=&capacite_min=&equipement=` | catalogue | `/salles` |
| `GET /api/salles/{id}` | catalogue | `/salles/{id}` |
| `GET /api/disponibilites/{salle_id}?date=AAAA-MM-JJ` | disponibilites | `/disponibilites/{salle_id}` |
| `GET /api/reservations` | reservations | `/reservations` |
| `GET /api/reservations/{id}` | reservations | `/reservations/{id}` |
| `POST /api/reservations` | reservations | `/reservations` |

Non publiés : `POST /occupations` et `DELETE /occupations/{reservation_id}` (appelés par reservations seulement), les contrôles de santé des services.

## Ce que fait la passerelle

- **Jeton** : signature RS256 vérifiée avec la clé publique `cles/public.pem` (la clé privée `cles/prive.pem` ne sert qu'à `outils/jeton.mjs`) ; dates contrôlées par Spring Security (`exp`, `nbf`, 60 s de tolérance) ; trois validateurs ajoutés dans `SecuriteConfig` : émetteur `coworking-demo` exigé, `exp` et `sub` obligatoires. Un refus donne `401`, corps vide, détail dans `WWW-Authenticate`.
- **En-têtes** : `X-Utilisateur` (sujet du jeton ; un `X-Utilisateur` envoyé par le client est écrasé) et `X-Request-Id` (repris du client s'il en envoie un, créé sinon, renvoyé dans la réponse).
- **Limitation de débit** : un seau de jetons par utilisateur et par route, 5 requêtes par seconde, rafale de 10, compté dans Redis ; au-delà, `429` au corps vide, sans `Retry-After`. En-têtes `X-RateLimit-Remaining`, `X-RateLimit-Burst-Capacity`, `X-RateLimit-Replenish-Rate`, `X-RateLimit-Requested-Tokens`. Si Redis ne répond plus, la passerelle laisse passer la requête (`X-RateLimit-Remaining: -1`) après le délai de 1 s de `spring.data.redis.timeout` (réponse en 1,4 à 1,5 s mesurés), au lieu de 60 s par défaut.

## Contrats

`catalogue/openapi.yaml`, `disponibilites/openapi.yaml`, `reservations/openapi.yaml` décrivent désormais aussi l'accès par la passerelle : serveur `http://localhost:8080/api`, schéma de sécurité `jeton` (bearer, JWT), réponses `401` et `429` de la passerelle.

## Ce qui reste fragile (traité à l'état suivant)

Aucun délai d'attente explicite (ni dans reservations, ni dans la passerelle), aucune nouvelle tentative, aucun coupe-circuit ; `POST /occupations` n'est pas idempotent ; un appel abandonné peut laisser un créneau bloqué sans réservation.

## Arborescence

```
docker-compose.yml        les 7 conteneurs, volumes, controles de sante
cles/                     paire RSA de demonstration (prive.pem, public.pem)
outils/jeton.mjs          emet un jeton JWT RS256 signe avec cles/prive.pem
outils/generer-cles.sh    regenere la paire de cles
passerelle/               Spring Cloud Gateway : application.yml (routes,
                          debit, cle publique, Redis), SecuriteConfig
                          (jeton et validateurs), EnTetesFilter
catalogue/                Express : src/index.js, db/init.sql, openapi.yaml
disponibilites/           FastAPI : app/main.py, app/init.sql, openapi.yaml
reservations/             Spring Boot : ReservationController, Reservation,
                          db/init.sql, openapi.yaml
```

Clé privée de démonstration et mots de passe en clair : à régénérer (`bash outils/generer-cles.sh`) et à ne jamais reprendre ailleurs.

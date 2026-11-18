"""Service notifications : consomme les confirmations de reservation.

L'envoi reel (courriel, SMS) est simule par une ligne de journal.
"""

import json
import os
import signal
import sqlite3
import sys
import time

import pika

URL = os.environ["RABBITMQ_URL"]
ECHANGE = "reservations"
FILE = "notifications.reservations"
# Panne simulee : secondes d'attente entre l'envoi et l'accuse de
# reception (fenetre pendant laquelle un arret brutal fait des degats)
PAUSE_AVANT_ACK = float(os.environ.get("PAUSE_AVANT_ACK", "0"))
# Memoire des reservations deja confirmees : un fichier SQLite sur un
# volume (docker-compose.yml). Elle survit au redemarrage et a la
# recreation du conteneur ; docker compose down -v l'efface.
BASE = os.environ.get("BASE_SQLITE", "/donnees/notifications.db")

deja = sqlite3.connect(BASE)
deja.execute("CREATE TABLE IF NOT EXISTS confirmee (id TEXT PRIMARY KEY)")


def recevoir(canal, livraison, proprietes, corps):
    resa = json.loads(corps)
    # redelivered : RabbitMQ a deja livre ce message sans recevoir d'accuse
    rejeu = " (message redistribué)" if livraison.redelivered else ""
    # Consommateur idempotent : une reservation deja confirmee ne l'est
    # pas une seconde fois, mais on accuse reception du doublon
    if deja.execute("SELECT 1 FROM confirmee WHERE id = ?",
                    (resa["id"],)).fetchone():
        print(f"Réservation {resa['id'][:8]} déjà confirmée, "
              f"message ignoré{rejeu}", flush=True)
        canal.basic_ack(livraison.delivery_tag)
        return
    print(f"Confirmation envoyée à {resa['utilisateur']} : salle "
          f"{resa['salle_id']}, du {resa['debut']} au {resa['fin']}, "
          f"réservation {resa['id'][:8]}{rejeu}", flush=True)
    # Noter l'envoi avant l'accuse de reception (un arret brutal entre
    # l'envoi et cette ecriture renverrait encore la confirmation)
    with deja:  # transaction : COMMIT a la sortie du bloc
        deja.execute("INSERT INTO confirmee VALUES (?)", (resa["id"],))
    time.sleep(PAUSE_AVANT_ACK)
    # Accuse de reception : RabbitMQ peut retirer le message de la file
    canal.basic_ack(livraison.delivery_tag)


# docker stop envoie SIGTERM : on sort proprement
signal.signal(signal.SIGTERM, lambda *args: sys.exit(0))

connexion = pika.BlockingConnection(pika.URLParameters(URL))
canal = connexion.channel()
# Echange, file durable et liaison : les memes que ceux du producteur
# (reservations les declare aussi), pour demarrer dans n'importe quel ordre
canal.exchange_declare(ECHANGE, "topic", durable=True)
canal.queue_declare(FILE, durable=True)
canal.queue_bind(FILE, ECHANGE, routing_key="reservation.confirmee")
canal.basic_qos(prefetch_count=1)  # un seul message non acquitte a la fois
# auto_ack a False (valeur par defaut) : accuse de reception manuel,
# envoye par recevoir() une fois la confirmation traitee
canal.basic_consume(FILE, recevoir, auto_ack=False)
print(f"En attente des confirmations sur la file {FILE}", flush=True)
canal.start_consuming()

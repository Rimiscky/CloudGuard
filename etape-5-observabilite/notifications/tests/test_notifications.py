"""Tests du consommateur notifications : recevoir() appelee comme le
ferait pika, sans RabbitMQ (canal et livraison simules), sur un fichier
SQLite jetable."""

import importlib
import json
import os
import tempfile
from types import SimpleNamespace

# Memoire de test jetable, fixee avant l'import du consommateur
os.environ["BASE_SQLITE"] = os.path.join(tempfile.mkdtemp(), "test.db")

import notifications


class CanalSimule:
    """Retient les accuses de reception au lieu de les envoyer."""

    def __init__(self):
        self.acquittes = []

    def basic_ack(self, delivery_tag):
        self.acquittes.append(delivery_tag)


def livrer(canal, resa_id, tag, redistribue):
    """Livre a recevoir() le message de la reservation resa_id."""
    corps = json.dumps({
        "id": resa_id, "salle_id": "LYO-01", "utilisateur": "alice",
        "debut": "2026-11-16T14:00:00", "fin": "2026-11-16T16:00:00",
    }).encode()
    livraison = SimpleNamespace(delivery_tag=tag, redelivered=redistribue)
    proprietes = SimpleNamespace(headers={"X-Request-Id": "test-0001"})
    notifications.recevoir(canal, livraison, proprietes, corps)


def lignes(capsys):
    """Les lignes JSON ecrites depuis le dernier appel."""
    sortie = capsys.readouterr().out
    return [json.loads(texte) for texte in sortie.splitlines()]


def test_message_redistribue_confirme_une_seule_fois(capsys):
    canal = CanalSimule()
    livrer(canal, "11111111-aaaa-4bbb-8ccc-000000000001", 1, False)
    # Le meme message, de nouveau livre (accuse jamais recu par RabbitMQ)
    livrer(canal, "11111111-aaaa-4bbb-8ccc-000000000001", 2, True)
    premiere, seconde = lignes(capsys)
    assert premiere["message"].startswith("Confirmation envoyée à alice")
    assert seconde["message"] == (
        "Réservation 11111111 déjà confirmée, "
        "message ignoré (message redistribué)")
    assert seconde["request_id"] == "test-0001"
    assert canal.acquittes == [1, 2]  # le doublon est acquitte aussi


def test_memoire_survit_au_redemarrage(capsys):
    canal = CanalSimule()
    livrer(canal, "22222222-aaaa-4bbb-8ccc-000000000002", 1, False)
    # Recharger le module rouvre le fichier SQLite, comme un redemarrage
    importlib.reload(notifications)
    livrer(canal, "22222222-aaaa-4bbb-8ccc-000000000002", 2, False)
    messages = [ligne["message"] for ligne in lignes(capsys)]
    assert messages[0].startswith("Confirmation envoyée")
    assert messages[1].startswith("Réservation 22222222 déjà confirmée")
    assert canal.acquittes == [1, 2]

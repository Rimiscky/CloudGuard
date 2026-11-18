"""Tests du service disponibilites : l'application FastAPI et un vrai
fichier SQLite (vide au depart, rempli par init.sql), sans reseau.
Les reponses sont aussi comparees au contrat ecrit (openapi.yaml)."""

import os
import tempfile

# Base de test jetable, fixee avant l'import de l'application
os.environ["BASE_SQLITE"] = os.path.join(tempfile.mkdtemp(), "test.db")

import pytest
import yaml
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator
from referencing import Registry
from referencing.jsonschema import DRAFT202012

from main import app

# Le contrat ecrit a la main ; OpenAPI 3.1 decrit ses schemas en
# JSON Schema 2020-12, que la bibliotheque jsonschema sait verifier
with open("openapi.yaml", encoding="utf-8") as fichier:
    CONTRAT = yaml.safe_load(fichier)
REGISTRE = Registry().with_resource(
    "urn:contrat", DRAFT202012.create_resource(CONTRAT))


def conforme(corps, schema):
    """Leve une erreur si corps ne respecte pas le schema du contrat."""
    reference = {"$ref": f"urn:contrat#/components/schemas/{schema}"}
    Draft202012Validator(reference, registry=REGISTRE).validate(corps)


@pytest.fixture(scope="module")
def client():
    # Le with declenche le cycle de vie : creation de la base (init.sql)
    with TestClient(app) as c:
        yield c


def test_planning_du_16_novembre(client):
    reponse = client.get("/disponibilites/LYO-01",
                         params={"date": "2026-11-16"})
    assert reponse.status_code == 200
    conforme(reponse.json(), "Planning")
    assert reponse.json()["creneaux_libres"] == [
        {"debut": "08:00", "fin": "09:00"},
        {"debut": "11:00", "fin": "16:00"},
        {"debut": "18:00", "fin": "20:00"},
    ]


def test_occuper_puis_chevauchement_refuse(client):
    occupation = {"reservation_id": "test-1", "salle_id": "NAN-02",
                  "debut": "2026-12-01T10:00:00",
                  "fin": "2026-12-01T12:00:00"}
    reponse = client.post("/occupations", json=occupation)
    assert reponse.status_code == 201
    conforme(reponse.json(), "Occupation")

    autre = {**occupation, "reservation_id": "test-2",
             "debut": "2026-12-01T11:00:00"}
    reponse = client.post("/occupations", json=autre)
    assert reponse.status_code == 409
    conforme(reponse.json(), "Erreur")
    assert reponse.json()["detail"] == "Créneau déjà occupé"


def test_hors_horaires_refuse(client):
    occupation = {"reservation_id": "test-3", "salle_id": "NAN-02",
                  "debut": "2026-12-01T19:00:00",
                  "fin": "2026-12-01T21:00:00"}
    reponse = client.post("/occupations", json=occupation)
    assert reponse.status_code == 422
    assert reponse.json()["detail"].startswith("Créneau invalide")


def test_liberer_une_seule_fois(client):
    assert client.delete("/occupations/test-1").status_code == 204
    reponse = client.delete("/occupations/test-1")
    assert reponse.status_code == 404
    conforme(reponse.json(), "Erreur")


def test_date_invalide(client):
    reponse = client.get("/disponibilites/LYO-01",
                         params={"date": "2026-13-01"})
    assert reponse.status_code == 422


def test_rejeu_idempotent(client):
    occupation = {"reservation_id": "test-4", "salle_id": "NAN-02",
                  "debut": "2026-12-02T10:00:00",
                  "fin": "2026-12-02T11:00:00"}
    assert client.post("/occupations", json=occupation).status_code == 201
    # La meme demande, renvoyee : 200, rien de plus n'est bloque
    reponse = client.post("/occupations", json=occupation)
    assert reponse.status_code == 200
    conforme(reponse.json(), "Occupation")
    planning = client.get("/disponibilites/NAN-02",
                          params={"date": "2026-12-02"}).json()
    assert planning["occupations"] == [{"debut": "10:00", "fin": "11:00"}]


def test_demande_en_retard_apres_annulation(client):
    # La compensation arrive avant la demande qu'elle annule
    assert client.delete("/occupations/test-5").status_code == 404
    occupation = {"reservation_id": "test-5", "salle_id": "NAN-02",
                  "debut": "2026-12-02T14:00:00",
                  "fin": "2026-12-02T15:00:00"}
    reponse = client.post("/occupations", json=occupation)
    assert reponse.status_code == 409
    assert reponse.json()["detail"] == "Réservation annulée"

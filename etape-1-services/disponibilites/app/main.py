"""Service disponibilites : creneaux libres et occupations des salles."""

import datetime as dt
import os
import sqlite3
from contextlib import asynccontextmanager, closing

from fastapi import FastAPI, HTTPException, Response
from pydantic import BaseModel, NaiveDatetime

BASE = os.environ.get("BASE_SQLITE", "/donnees/disponibilites.db")
OUVERTURE, FERMETURE = dt.time(8), dt.time(20)  # tous les jours


def ouvrir():
    # isolation_level=None : c'est nous qui ouvrons les transactions
    con = sqlite3.connect(BASE, timeout=5, isolation_level=None)
    con.row_factory = sqlite3.Row
    return con


@asynccontextmanager
async def cycle_de_vie(app):
    if not os.path.exists(BASE):  # premier demarrage : schema + donnees
        with closing(ouvrir()) as con, open("init.sql") as script:
            con.executescript(script.read())
    yield


app = FastAPI(title="Service disponibilites", lifespan=cycle_de_vie)


class Occupation(BaseModel):
    reservation_id: str
    salle_id: str
    debut: NaiveDatetime  # heure locale de l'espace, sans fuseau
    fin: NaiveDatetime


@app.get("/health")
def sante():
    return {"statut": "ok"}


@app.get("/disponibilites/{salle_id}")
def disponibilites(salle_id: str, date: dt.date):
    ouverture = dt.datetime.combine(date, OUVERTURE)
    fermeture = dt.datetime.combine(date, FERMETURE)
    with closing(ouvrir()) as con:
        occupees = con.execute(
            "SELECT debut, fin FROM occupation"
            " WHERE salle_id = ? AND debut < ? AND fin > ? ORDER BY debut",
            (salle_id, fermeture.isoformat(), ouverture.isoformat()),
        ).fetchall()
    # Les creneaux libres sont les trous entre les occupations
    libres, curseur = [], ouverture
    for occ in occupees:
        debut = dt.datetime.fromisoformat(occ["debut"])
        fin = dt.datetime.fromisoformat(occ["fin"])
        if debut > curseur:
            libres.append(creneau(curseur, debut))
        curseur = max(curseur, fin)
    if curseur < fermeture:
        libres.append(creneau(curseur, fermeture))
    return {
        "salle_id": salle_id,
        "date": date,
        "occupations": [
            creneau(dt.datetime.fromisoformat(o["debut"]),
                    dt.datetime.fromisoformat(o["fin"]))
            for o in occupees
        ],
        "creneaux_libres": libres,
    }


def creneau(debut, fin):
    return {"debut": debut.strftime("%H:%M"), "fin": fin.strftime("%H:%M")}


@app.post("/occupations", status_code=201)
def occuper(occ: Occupation):
    if not (occ.debut < occ.fin
            and occ.debut.date() == occ.fin.date()
            and OUVERTURE <= occ.debut.time()
            and occ.fin.time() <= FERMETURE):
        raise HTTPException(
            422, "Créneau invalide : même jour, entre 8 h et 20 h")
    with closing(ouvrir()) as con:
        # BEGIN IMMEDIATE verrouille l'ecriture : deux demandes simultanees
        # ne peuvent pas prendre le meme creneau
        con.execute("BEGIN IMMEDIATE")
        conflit = con.execute(
            "SELECT 1 FROM occupation"
            " WHERE salle_id = ? AND debut < ? AND fin > ?",
            (occ.salle_id, occ.fin.isoformat(), occ.debut.isoformat()),
        ).fetchone()
        if conflit:
            raise HTTPException(409, "Créneau déjà occupé")
        con.execute(
            "INSERT INTO occupation VALUES (?, ?, ?, ?)",
            (occ.reservation_id, occ.salle_id,
             occ.debut.isoformat(), occ.fin.isoformat()),
        )
        con.execute("COMMIT")  # sans COMMIT, la fermeture annule tout
    return occ


@app.delete("/occupations/{reservation_id}", status_code=204)
def liberer(reservation_id: str):
    with closing(ouvrir()) as con:
        supprimees = con.execute(
            "DELETE FROM occupation WHERE reservation_id = ?",
            (reservation_id,),
        ).rowcount
    if supprimees == 0:
        raise HTTPException(404, "Occupation inconnue")
    return Response(status_code=204)

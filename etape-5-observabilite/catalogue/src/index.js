// Service catalogue : espaces, salles et equipements du reseau.
import { randomUUID } from "node:crypto";
import express from "express";
import CircuitBreaker from "opossum";
import pRetry from "p-retry";
import pg from "pg";

const PORT = process.env.PORT ?? 3000;

// Une ligne JSON par evenement, avec les champs des services Java (ECS)
function journal(niveau, message, champs = {}) {
  console.log(JSON.stringify({
    "@timestamp": new Date().toISOString(),
    log: { level: niveau },
    service: { name: "catalogue" },
    message,
    ...champs,
  }));
}

const DISPONIBILITES = process.env.DISPONIBILITES_URL
  ?? "http://disponibilites:8000";
// DATABASE_URL est fournie par docker-compose.yml
const base = new pg.Pool({ connectionString: process.env.DATABASE_URL });
// Une connexion inactive du pool recoit une erreur si la base s'arrete :
// sans cet ecouteur, l'evenement "error" non gere arrete tout le service.
// Le pool retire lui-meme la connexion fautive.
base.on("error", (erreur) => {
  journal("WARN", `Connexion inactive a la base perdue : ${erreur.message}`);
});
const app = express();
// Pas d'en-tete "X-Powered-By: Express" : inutile d'annoncer l'outil
app.disable("x-powered-by");

// X-Request-Id pose par la passerelle (cree ici s'il manque), puis une
// ligne de journal par requete, une fois la reponse envoyee
app.use((req, res, next) => {
  const debut = performance.now();
  res.locals.requestId = req.get("X-Request-Id") ?? randomUUID();
  res.on("finish", () => {
    if (req.path === "/health") return; // controles de sante : silence
    journal(res.statusCode >= 500 ? "ERROR" : "INFO",
      `${req.method} ${req.path} ${res.statusCode}`, {
        request_id: res.locals.requestId,
        methode: req.method,
        chemin: req.path,
        statut: res.statusCode,
        duree_ms: Math.round(performance.now() - debut),
      });
  });
  next();
});

app.get("/health", (req, res) => {
  res.json({ statut: "ok" });
});

app.get("/espaces", async (req, res) => {
  const { rows } = await base.query("SELECT * FROM espace ORDER BY id");
  res.json(rows);
});

app.get("/equipements", async (req, res) => {
  const { rows } = await base.query("SELECT * FROM equipement ORDER BY code");
  res.json(rows);
});

// Creneaux libres d'une salle un jour donne, lus dans disponibilites.
// Delai de 1 s par appel : sans lui, fetch attendrait jusqu'a 300 s.
// X-Request-Id suit la requete d'origine jusqu'a disponibilites.
async function lireCreneaux(salleId, date, requestId) {
  const reponse = await fetch(
    `${DISPONIBILITES}/disponibilites/${salleId}?date=${date}`,
    {
      signal: AbortSignal.timeout(1000),
      headers: { "X-Request-Id": requestId },
    },
  );
  if (!reponse.ok) {
    throw new Error(`HTTP ${reponse.status}`);
  }
  return (await reponse.json()).creneaux_libres;
}

// Coupe-circuit : ouvert quand la moitie des appels des 10 dernieres
// secondes echouent (des 5 appels), nouvel essai 10 s plus tard
const circuit = new CircuitBreaker(lireCreneaux, {
  timeout: false, // le delai est deja pose sur fetch
  errorThresholdPercentage: 50,
  volumeThreshold: 5,
  rollingCountTimeout: 10000,
  resetTimeout: 10000,
  // COUPE_CIRCUIT=0 le desactive, pour comparer (jamais en production)
  enabled: process.env.COUPE_CIRCUIT !== "0",
});
for (const [evenement, etat] of [
  ["open", "OPEN"], ["halfOpen", "HALF_OPEN"], ["close", "CLOSED"],
]) {
  circuit.on(evenement, () =>
    journal("WARN", `Coupe-circuit disponibilites : ${etat}`));
}

// Deux nouvelles tentatives (apres 200 puis 400 ms), sauf circuit ouvert
const creneauxLibres = (salleId, date, requestId) =>
  pRetry(() => circuit.fire(salleId, date, requestId), {
    retries: 2,
    minTimeout: 200,
    factor: 2,
    shouldRetry: ({ error }) => error.code !== "EOPENBREAKER",
    onFailedAttempt: ({ error, attemptNumber }) =>
      journal("WARN", `disponibilites ${salleId} : tentative `
        + `${attemptNumber} en echec (${error.message})`,
      { request_id: requestId }),
  });

// Filtres facultatifs : /salles?ville=Lyon&capacite_min=6&equipement=visio
// Avec date, debut et fin (date=2026-11-16&debut=14:00&fin=16:00), chaque
// salle porte "libre" : true, false, ou null si ses disponibilites n'ont
// pas pu etre lues (reponse partielle plutot qu'une erreur).
app.get("/salles", async (req, res) => {
  const { ville, capacite_min, equipement, date, debut, fin } = req.query;
  if (capacite_min !== undefined && !/^\d+$/.test(capacite_min)) {
    return res
      .status(400)
      .json({ detail: "capacite_min doit être un entier positif" });
  }
  const creneau = date !== undefined || debut !== undefined
    || fin !== undefined;
  if (creneau && !(/^\d{4}-\d{2}-\d{2}$/.test(date)
      && /^\d{2}:\d{2}$/.test(debut) && /^\d{2}:\d{2}$/.test(fin))) {
    return res.status(400).json({
      detail: "date (AAAA-MM-JJ), debut et fin (HH:MM) vont ensemble",
    });
  }
  const { rows } = await base.query(
    `SELECT * FROM salle_detail
     WHERE ($1::text IS NULL OR ville = $1)
       AND ($2::int IS NULL OR capacite >= $2)
       AND ($3::text IS NULL OR $3 = ANY (equipements))
     ORDER BY id`,
    [ville ?? null, capacite_min ?? null, equipement ?? null],
  );
  if (creneau) {
    const lus = await Promise.allSettled(
      rows.map((salle) =>
        creneauxLibres(salle.id, date, res.locals.requestId)),
    );
    lus.forEach((lu, i) => {
      rows[i].libre = lu.status === "fulfilled"
        ? lu.value.some((c) => c.debut <= debut && fin <= c.fin)
        : null;
    });
    if (lus.some((lu) => lu.status === "rejected")) {
      res.set("X-Disponibilites", "inconnues");
    }
  }
  res.json(rows);
});

app.get("/salles/:id", async (req, res) => {
  const { rows } = await base.query(
    "SELECT * FROM salle_detail WHERE id = $1",
    [req.params.id],
  );
  if (rows.length === 0) {
    return res
      .status(404)
      .json({ detail: `Salle inconnue : ${req.params.id}` });
  }
  res.json(rows[0]);
});

// Erreur imprevue (base injoignable...) : 500 en JSON, cause au journal
app.use((err, req, res, next) => {
  journal("ERROR", err.message, { request_id: res.locals.requestId });
  res.status(500).json({ detail: "Erreur interne du catalogue" });
});

const serveur = app.listen(PORT, () => {
  journal("INFO", `Catalogue à l'écoute sur le port ${PORT}`);
});

// docker stop envoie SIGTERM : on ferme proprement au lieu d'attendre
process.on("SIGTERM", () => serveur.close(() => base.end()));

// Service catalogue : espaces, salles et equipements du reseau.
import express from "express";
import pg from "pg";

const PORT = process.env.PORT ?? 3000;
// DATABASE_URL est fournie par docker-compose.yml
const base = new pg.Pool({ connectionString: process.env.DATABASE_URL });
// Une connexion inactive du pool recoit une erreur si la base s'arrete :
// sans cet ecouteur, l'evenement "error" non gere arrete tout le service.
// Le pool retire lui-meme la connexion fautive.
base.on("error", (erreur) => {
  console.error(`Connexion inactive a la base perdue : ${erreur.message}`);
});
const app = express();
// Pas d'en-tete "X-Powered-By: Express" : inutile d'annoncer l'outil
app.disable("x-powered-by");

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

// Filtres facultatifs : /salles?ville=Lyon&capacite_min=6&equipement=visio
app.get("/salles", async (req, res) => {
  const { ville, capacite_min, equipement } = req.query;
  if (capacite_min !== undefined && !/^\d+$/.test(capacite_min)) {
    return res
      .status(400)
      .json({ detail: "capacite_min doit être un entier positif" });
  }
  const { rows } = await base.query(
    `SELECT * FROM salle_detail
     WHERE ($1::text IS NULL OR ville = $1)
       AND ($2::int IS NULL OR capacite >= $2)
       AND ($3::text IS NULL OR $3 = ANY (equipements))
     ORDER BY id`,
    [ville ?? null, capacite_min ?? null, equipement ?? null],
  );
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

const serveur = app.listen(PORT, () => {
  console.log(`Catalogue à l'écoute sur le port ${PORT}`);
});

// docker stop envoie SIGTERM : on ferme proprement au lieu d'attendre
process.on("SIGTERM", () => serveur.close(() => base.end()));

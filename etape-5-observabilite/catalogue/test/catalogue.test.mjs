// Tests d'integration du catalogue : le vrai service et sa vraie base,
// chargee avec les donnees de demonstration (catalogue/db/init.sql).
// Lances dans un conteneur sur le reseau Compose :
//   docker compose run --rm tests-catalogue
import { test } from "node:test";
import assert from "node:assert/strict";

const URL_CATALOGUE = process.env.URL_CATALOGUE ?? "http://catalogue:3000";

async function lire(chemin) {
  const reponse = await fetch(URL_CATALOGUE + chemin);
  return { statut: reponse.status, corps: await reponse.json() };
}

test("quatre espaces, tries par identifiant", async () => {
  const { statut, corps } = await lire("/espaces");
  assert.equal(statut, 200);
  assert.deepEqual(corps.map((e) => e.id), ["BDX", "LIL", "LYO", "NAN"]);
});

test("filtres combines : ville et capacite minimale", async () => {
  const { corps } = await lire("/salles?ville=Lyon&capacite_min=6");
  assert.deepEqual(corps.map((s) => s.id), ["LYO-01", "LYO-03"]);
});

test("filtre par equipement", async () => {
  const { corps } = await lire("/salles?equipement=sono");
  assert.deepEqual(corps.map((s) => s.id), ["BDX-02", "LYO-03", "NAN-03"]);
});

test("capacite_min qui n'est pas un entier : 400", async () => {
  const { statut, corps } = await lire("/salles?capacite_min=six");
  assert.equal(statut, 400);
  assert.equal(corps.detail, "capacite_min doit être un entier positif");
});

test("salle inconnue : 404 avec un champ detail", async () => {
  const { statut, corps } = await lire("/salles/LYO-99");
  assert.equal(statut, 404);
  assert.equal(corps.detail, "Salle inconnue : LYO-99");
});

// Contrat avec le consommateur : le service reservations lit id,
// capacite et prix_horaire dans GET /salles/{id}. Si l'un d'eux change
// de nom ou de type, ce test echoue avant que reservations ne casse.
test("contrat avec reservations : GET /salles/{id}", async () => {
  const { statut, corps } = await lire("/salles/LYO-02");
  assert.equal(statut, 200);
  assert.equal(corps.id, "LYO-02");
  assert.ok(Number.isInteger(corps.capacite), "capacite entiere");
  assert.equal(typeof corps.prix_horaire, "number", "prix_horaire nombre");
  assert.equal(corps.prix_horaire, 25);
});

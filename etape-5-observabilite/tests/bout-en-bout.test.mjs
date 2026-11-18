// Test de bout en bout : un parcours d'utilisateur complet, a travers la
// passerelle, comme le ferait un client (jeton, routes publiques).
// Lance dans un conteneur sur le reseau Compose :
//   docker compose run --rm tests-bout-en-bout
// Il lit l'etat avant d'agir (premier creneau libre) : on peut le rejouer
// sans remettre les donnees a zero.
import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";

const API = process.env.URL_API ?? "http://passerelle:8080/api";
const jeton = (utilisateur) =>
  execFileSync("node", ["/systeme/outils/jeton.mjs", utilisateur])
    .toString().trim();
const ALICE = jeton("alice");

async function appeler(chemin, options = {}) {
  const reponse = await fetch(API + chemin, {
    ...options,
    headers: {
      Authorization: `Bearer ${ALICE}`,
      "Content-Type": "application/json",
      ...options.headers,
    },
  });
  const texte = await reponse.text();
  return {
    statut: reponse.status,
    requestId: reponse.headers.get("x-request-id"),
    corps: texte ? JSON.parse(texte) : null,
  };
}

test("sans jeton, la passerelle refuse (401)", async () => {
  const reponse = await fetch(API + "/espaces");
  assert.equal(reponse.status, 401);
});

test("chercher, reserver, retrouver sa reservation", async (t) => {
  // 1. Une salle de Nantes pour 6 personnes avec un ecran
  const salles = await appeler(
    "/salles?ville=Nantes&capacite_min=6&equipement=ecran");
  assert.equal(salles.statut, 200);
  assert.deepEqual(salles.corps.map((s) => s.id), ["NAN-01", "NAN-02"]);

  // 2. Son premier creneau libre le 1er decembre
  const jour = "2026-12-01";
  const planning = await appeler(`/disponibilites/NAN-02?date=${jour}`);
  assert.equal(planning.statut, 200);
  const libre = planning.corps.creneaux_libres[0];
  assert.ok(libre, "plus aucun creneau libre ce jour-la");
  const heure = Number(libre.debut.slice(0, 2));
  const fin = `${String(heure + 1).padStart(2, "0")}:00`;

  // 3. Reserver une heure, avec un identifiant de requete choisi,
  //    different a chaque passage, pour retrouver ce parcours seul
  //    dans les journaux
  const requestId = `bout-en-bout-${Date.now()}`;
  t.diagnostic(`X-Request-Id : ${requestId}`);
  const reservation = await appeler("/reservations", {
    method: "POST",
    headers: { "X-Request-Id": requestId },
    body: JSON.stringify({
      salle_id: "NAN-02",
      debut: `${jour}T${libre.debut}:00`,
      fin: `${jour}T${fin}:00`,
      participants: 6,
    }),
  });
  assert.equal(reservation.statut, 201);
  assert.equal(reservation.requestId, requestId);
  assert.equal(reservation.corps.utilisateur, "alice");
  assert.equal(reservation.corps.montant, 35);

  // 4. Le creneau n'est plus libre, la reservation est dans la liste
  const apres = await appeler(`/disponibilites/NAN-02?date=${jour}`);
  assert.ok(apres.corps.occupations.some((o) => o.debut === libre.debut));
  const mes = await appeler("/reservations");
  assert.ok(mes.corps.some((r) => r.id === reservation.corps.id));
});

test("un X-Utilisateur envoye par le client est ignore", async () => {
  const mes = await appeler("/reservations", {
    headers: { "X-Utilisateur": "bruno" },
  });
  assert.equal(mes.statut, 200);
  assert.ok(mes.corps.length > 0);
  assert.ok(mes.corps.every((r) => r.utilisateur === "alice"));
});

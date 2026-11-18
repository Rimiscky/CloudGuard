// Parcours d'une requete : les lignes JSON des journaux, remises dans
// l'ordre chronologique et resumees (heure, service, duree, message).
// Usage :
//   docker compose logs --no-log-prefix | grep -E '"request_id":"X"' \
//     | node outils/parcours.mjs
import { createInterface } from "node:readline";

const lignes = [];
for await (const texte of createInterface({ input: process.stdin })) {
  try {
    lignes.push(JSON.parse(texte));
  } catch {
    // ligne qui n'est pas du JSON (demarrage d'Uvicorn...) : ignoree
  }
}
// Horodatages ISO 8601 en UTC partout : l'ordre alphabetique est
// l'ordre chronologique (a la milliseconde pres)
lignes.sort((a, b) => (a["@timestamp"] < b["@timestamp"] ? -1 : 1));
for (const ligne of lignes) {
  const heure = ligne["@timestamp"].slice(11, 23);
  const service = (ligne.service?.name ?? "?").padEnd(15);
  const duree = ligne.duree_ms === undefined ? "" : `${ligne.duree_ms} ms`;
  console.log(`${heure}  ${service}${duree.padStart(8)}  ${ligne.message}`);
}

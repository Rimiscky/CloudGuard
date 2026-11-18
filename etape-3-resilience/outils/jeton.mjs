// Emet un jeton JWT signe en RS256 avec la cle privee de demonstration.
// Usage : node outils/jeton.mjs <utilisateur> [duree_en_secondes]
// Une duree negative donne un jeton deja expire (pour les essais).
import { sign } from "node:crypto";
import { readFileSync } from "node:fs";

const [utilisateur = "alice", duree = "3600"] = process.argv.slice(2);
const clePrivee = readFileSync(new URL("../cles/prive.pem", import.meta.url));
const base64url = (objet) =>
  Buffer.from(JSON.stringify(objet)).toString("base64url");

const maintenant = Math.floor(Date.now() / 1000);
const expiration = maintenant + Number(duree);
const entete = base64url({ alg: "RS256", typ: "JWT" });
const charge = base64url({
  iss: "coworking-demo",
  sub: utilisateur,
  iat: Math.min(maintenant, expiration - 60), // emis avant d'expirer
  exp: expiration,
});
const signature = sign("sha256", Buffer.from(`${entete}.${charge}`), clePrivee);
console.log(`${entete}.${charge}.${signature.toString("base64url")}`);

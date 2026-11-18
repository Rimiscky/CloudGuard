-- Base du service catalogue : espaces, salles et equipements.
-- Executee par l'image postgres au premier demarrage (volume vide).

CREATE TABLE espace (
  id       text PRIMARY KEY,
  nom      text NOT NULL,
  ville    text NOT NULL,
  quartier text NOT NULL
);

CREATE TABLE salle (
  id           text PRIMARY KEY,
  espace_id    text NOT NULL REFERENCES espace (id),
  nom          text NOT NULL,
  capacite     integer NOT NULL CHECK (capacite > 0),
  prix_horaire numeric(6, 2) NOT NULL CHECK (prix_horaire > 0)
);

CREATE TABLE equipement (
  code    text PRIMARY KEY,
  libelle text NOT NULL
);

CREATE TABLE salle_equipement (
  salle_id        text REFERENCES salle (id),
  equipement_code text REFERENCES equipement (code),
  PRIMARY KEY (salle_id, equipement_code)
);

-- Vue lue par le service : une ligne par salle, equipements en tableau
CREATE VIEW salle_detail AS
SELECT s.id, s.espace_id, e.ville, s.nom, s.capacite,
       s.prix_horaire::float8 AS prix_horaire,
       array_remove(array_agg(se.equipement_code
                              ORDER BY se.equipement_code), NULL)
         AS equipements
FROM salle s
JOIN espace e ON e.id = s.espace_id
LEFT JOIN salle_equipement se ON se.salle_id = s.id
GROUP BY s.id, e.ville;

INSERT INTO espace VALUES
  ('LYO', 'Lyon Part-Dieu', 'Lyon', 'Part-Dieu'),
  ('NAN', 'Nantes Île de Nantes', 'Nantes', 'Île de Nantes'),
  ('LIL', 'Lille Euralille', 'Lille', 'Euralille'),
  ('BDX', 'Bordeaux Chartrons', 'Bordeaux', 'Chartrons');

INSERT INTO salle VALUES
  ('LYO-01', 'LYO', 'Salle Rhône', 8, 45.00),
  ('LYO-02', 'LYO', 'Salle Saône', 4, 25.00),
  ('LYO-03', 'LYO', 'Salle Fourvière', 14, 80.00),
  ('LYO-04', 'LYO', 'Bureau Croix-Rousse', 2, 15.00),
  ('NAN-01', 'NAN', 'Salle Loire', 10, 55.00),
  ('NAN-02', 'NAN', 'Salle Erdre', 6, 35.00),
  ('NAN-03', 'NAN', 'Atelier des Machines', 20, 110.00),
  ('LIL-01', 'LIL', 'Salle Vauban', 8, 40.00),
  ('LIL-02', 'LIL', 'Salle Beffroi', 12, 65.00),
  ('LIL-03', 'LIL', 'Bureau Deûle', 3, 18.00),
  ('BDX-01', 'BDX', 'Salle Garonne', 6, 35.00),
  ('BDX-02', 'BDX', 'Salle Quinconces', 16, 90.00),
  ('BDX-03', 'BDX', 'Salle Bacalan', 4, 22.00);

INSERT INTO equipement VALUES
  ('ecran', 'Écran 65 pouces'),
  ('visio', 'Visioconférence'),
  ('projecteur', 'Vidéoprojecteur'),
  ('tableau-blanc', 'Tableau blanc'),
  ('paperboard', 'Paperboard'),
  ('sono', 'Sonorisation et micros');

INSERT INTO salle_equipement VALUES
  ('LYO-01', 'ecran'), ('LYO-01', 'visio'), ('LYO-01', 'tableau-blanc'),
  ('LYO-02', 'ecran'), ('LYO-02', 'tableau-blanc'),
  ('LYO-03', 'projecteur'), ('LYO-03', 'visio'), ('LYO-03', 'sono'),
  ('LYO-03', 'paperboard'),
  ('LYO-04', 'ecran'),
  ('NAN-01', 'ecran'), ('NAN-01', 'visio'), ('NAN-01', 'paperboard'),
  ('NAN-02', 'ecran'), ('NAN-02', 'tableau-blanc'),
  ('NAN-03', 'projecteur'), ('NAN-03', 'sono'), ('NAN-03', 'paperboard'),
  ('NAN-03', 'tableau-blanc'),
  ('LIL-01', 'ecran'), ('LIL-01', 'visio'),
  ('LIL-02', 'projecteur'), ('LIL-02', 'visio'), ('LIL-02', 'tableau-blanc'),
  ('LIL-03', 'ecran'),
  ('BDX-01', 'ecran'), ('BDX-01', 'visio'), ('BDX-01', 'tableau-blanc'),
  ('BDX-02', 'projecteur'), ('BDX-02', 'visio'), ('BDX-02', 'sono'),
  ('BDX-03', 'tableau-blanc');

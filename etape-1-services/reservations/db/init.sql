-- Base du service reservations.
-- Executee par l'image postgres au premier demarrage (volume vide).

CREATE TABLE reservation (
  id           text PRIMARY KEY,
  salle_id     text NOT NULL,          -- identifiant du service catalogue
  utilisateur  text NOT NULL,          -- sujet du jeton, transmis par
                                       -- la passerelle (X-Utilisateur)
  debut        timestamp NOT NULL,     -- heure locale de l'espace
  fin          timestamp NOT NULL,
  participants integer NOT NULL CHECK (participants > 0),
  montant      numeric(8, 2) NOT NULL,
  statut       text NOT NULL,
  cree_le      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX reservation_utilisateur ON reservation (utilisateur);

INSERT INTO reservation VALUES
  ('4ab1bcad-cf32-453f-a87d-6797ecc72e41', 'LYO-01', 'alice',
   '2026-11-16 09:00', '2026-11-16 11:00', 6, 90.00, 'CONFIRMEE',
   '2026-10-02 08:41:12+00'),
  ('9243f810-555e-4b9f-b420-68ee08dc6e7b', 'LYO-03', 'bruno',
   '2026-11-16 14:00', '2026-11-16 17:00', 12, 240.00, 'CONFIRMEE',
   '2026-10-03 14:05:47+00'),
  ('6113dd74-2dbf-418d-9a3b-d99b7503629a', 'LYO-02', 'chloe',
   '2026-11-17 10:00', '2026-11-17 11:30', 3, 37.50, 'CONFIRMEE',
   '2026-10-05 09:12:03+00'),
  ('beed8c71-3d01-4f78-bb63-71e49b4f8280', 'NAN-01', 'david',
   '2026-11-16 09:00', '2026-11-16 12:00', 8, 165.00, 'CONFIRMEE',
   '2026-10-06 16:30:55+00'),
  ('3c5c1fd4-75fd-41fd-830c-ccf7f9cf275d', 'NAN-03', 'emma',
   '2026-11-18 13:00', '2026-11-18 18:00', 18, 550.00, 'CONFIRMEE',
   '2026-10-08 10:02:19+00'),
  ('56231e95-6115-401b-a121-32f4a348200a', 'LIL-02', 'alice',
   '2026-11-19 09:30', '2026-11-19 12:00', 10, 162.50, 'CONFIRMEE',
   '2026-10-09 07:58:40+00'),
  ('58ca527b-40fe-469d-ba37-48c9193f1e19', 'LIL-01', 'farid',
   '2026-11-16 15:00', '2026-11-16 16:00', 5, 40.00, 'CONFIRMEE',
   '2026-10-12 12:44:08+00'),
  ('a1ff7093-c9ec-4f0f-b8c1-f23088931331', 'BDX-02', 'chloe',
   '2026-11-20 09:00', '2026-11-20 12:00', 15, 270.00, 'CONFIRMEE',
   '2026-10-13 15:21:36+00'),
  ('a4a2a3f9-a612-45e9-b2ce-1bc6b00cee5d', 'BDX-01', 'bruno',
   '2026-11-16 08:00', '2026-11-16 10:00', 4, 70.00, 'CONFIRMEE',
   '2026-10-14 08:09:51+00'),
  ('e94a0aa2-9e85-4971-bea2-342b1711df78', 'LYO-01', 'emma',
   '2026-11-16 16:00', '2026-11-16 18:00', 7, 90.00, 'CONFIRMEE',
   '2026-10-15 17:36:27+00');

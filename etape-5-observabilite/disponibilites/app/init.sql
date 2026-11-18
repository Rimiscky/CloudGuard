-- Stockage du service disponibilites (SQLite) : les creneaux occupes.
-- Execute une seule fois, quand le fichier de base n'existe pas encore.

CREATE TABLE occupation (
  reservation_id TEXT PRIMARY KEY,
  salle_id       TEXT NOT NULL,
  debut          TEXT NOT NULL,  -- AAAA-MM-JJTHH:MM:SS, heure locale
  fin            TEXT NOT NULL
);

-- Creneaux des reservations deja confirmees (memes identifiants que
-- dans la base du service reservations)
INSERT INTO occupation VALUES
  ('4ab1bcad-cf32-453f-a87d-6797ecc72e41', 'LYO-01',
   '2026-11-16T09:00:00', '2026-11-16T11:00:00'),
  ('9243f810-555e-4b9f-b420-68ee08dc6e7b', 'LYO-03',
   '2026-11-16T14:00:00', '2026-11-16T17:00:00'),
  ('6113dd74-2dbf-418d-9a3b-d99b7503629a', 'LYO-02',
   '2026-11-17T10:00:00', '2026-11-17T11:30:00'),
  ('beed8c71-3d01-4f78-bb63-71e49b4f8280', 'NAN-01',
   '2026-11-16T09:00:00', '2026-11-16T12:00:00'),
  ('3c5c1fd4-75fd-41fd-830c-ccf7f9cf275d', 'NAN-03',
   '2026-11-18T13:00:00', '2026-11-18T18:00:00'),
  ('56231e95-6115-401b-a121-32f4a348200a', 'LIL-02',
   '2026-11-19T09:30:00', '2026-11-19T12:00:00'),
  ('58ca527b-40fe-469d-ba37-48c9193f1e19', 'LIL-01',
   '2026-11-16T15:00:00', '2026-11-16T16:00:00'),
  ('a1ff7093-c9ec-4f0f-b8c1-f23088931331', 'BDX-02',
   '2026-11-20T09:00:00', '2026-11-20T12:00:00'),
  ('a4a2a3f9-a612-45e9-b2ce-1bc6b00cee5d', 'BDX-01',
   '2026-11-16T08:00:00', '2026-11-16T10:00:00'),
  ('e94a0aa2-9e85-4971-bea2-342b1711df78', 'LYO-01',
   '2026-11-16T16:00:00', '2026-11-16T18:00:00');

-- Opinię o rezerwacji zapisuje engagement-service i zgłasza tutaj, żeby klient nie widział „Wystaw opinię” drugi raz.
ALTER TABLE bookings ADD COLUMN reviewed boolean NOT NULL DEFAULT false;

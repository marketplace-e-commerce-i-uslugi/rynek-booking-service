# rynek-booking-service

`booking-service` — rezerwacje usług

> Status: **szkielet**. Wszystkie endpointy z tabeli istnieją w kontrolerach (`src/main/java/.../api/`) i zwracają `501 Not Implemented` (RFC 7807). Ciała żądań to puste rekordy w `api/dto/`.

## Uruchomienie

```bash
./mvnw spring-boot:run   # port 8093, zmienna PORT
./mvnw verify            # testy
```

- Swagger UI: http://localhost:8093/swagger-ui.html
- Zdrowie: http://localhost:8093/actuator/health

## Zakres i endpointy

_Skopiowane z `rynek-docs/architektura/rynek-endpointy-mikroserwisy.md`. Od teraz to repozytorium jest właścicielem tej specyfikacji._

Ekrany: 4 Karta usługi (kalendarz), 8 Rezerwacja usługi.

| Metoda | Ścieżka | Opis | Dostęp |
|---|---|---|---|
| GET | `/api/v1/services/{offerId}/availability` | Reguły dostępności usługodawcy | seller |
| PUT | `/api/v1/services/{offerId}/availability` | Godziny pracy, wyjątki, urlopy, czas trwania slotu | seller |
| GET | `/api/v1/services/{offerId}/slots?from=&to=` | Wolne sloty (kalendarz i godziny) | pub |
| GET | `/api/v1/services/{offerId}/next-slot` | Najbliższy termin (karta „Najbliższy termin”) | pub |
| POST | `/api/v1/bookings` | Rezerwacja slotu z krótkim przetrzymaniem (np. 10 min), adres i uwagi (`Idempotency-Key`) | user |
| GET | `/api/v1/bookings/mine?status=` | Moje rezerwacje | user |
| GET | `/api/v1/bookings/{bookingId}` | Szczegóły | user / seller |
| POST | `/api/v1/bookings/{bookingId}/cancel` | Anulowanie | user / seller |
| GET | `/api/v1/bookings/{bookingId}/calendar.ics` | Wydarzenie do kalendarza | user |
| GET | `/api/v1/seller/bookings?status=&from=` | Rezerwacje usługodawcy | seller |
| POST | `/api/v1/seller/bookings/{bookingId}/accept` | Akceptacja | seller |
| POST | `/api/v1/seller/bookings/{bookingId}/decline` | Odrzucenie | seller |
| POST | `/api/v1/seller/bookings/{bookingId}/propose-time` | Propozycja innego terminu | seller |
| POST | `/api/v1/seller/bookings/{bookingId}/complete` | Oznaczenie jako wykonane (uruchamia prośbę o opinię) | seller |

## Zdarzenia Kafka

Wiersze z §17, w których występuje `booking` (publikuje lub słucha).

| Zdarzenie | Publikuje | Słuchają |
|---|---|---|
| `payment.succeeded`, `payment.failed`, `payment.refunded` | payment | order, booking, notification |
| `booking.created`, `booking.confirmed`, `booking.cancelled`, `booking.completed` | booking | notification, analytics, engagement |

## Dane i zależności

- **Magazyn danych:** PostgreSQL
- **Zależności:** catalog-service, payment-service
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 6. `booking-service` — usługi.

## Do rozstrzygnięcia

Gateway (§2) kieruje ruch po prefiksie ścieżki, a poniższe endpointy leżą pod prefiksem innego serwisu. Możliwe rozwiązania: szczegółowe trasy w gateway przed trasami prefiksowymi albo zmiana ścieżek.

- `/api/v1/bookings/{bookingId}/review`: według dokumentu należy do `engagement-service`, ale prefiks w gateway kieruje do `booking-service`.

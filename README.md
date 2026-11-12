# rynek-booking-service

`booking-service` — rezerwacje usług

> Status: **rezerwacje działają** (PostgreSQL + Flyway, JWT z JWKS identity): reguły dostępności, wolne sloty, najbliższy termin, rezerwacja z przetrzymaniem na zaliczkę, decyzje usługodawcy, anulowanie ze zwrotem zaliczki, plik `.ics`. Zaliczki obsługuje payment-service (`referenceType: BOOKING`). Resztę ceny klient płaci poza Rynkiem.

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
| POST | `/api/v1/bookings/{bookingId}/accept-proposal` | Klient przyjmuje termin zaproponowany przez usługodawcę (dodane poza dokumentem architektury) | user |
| GET | `/internal/bookings/{bookingId}`, `/internal/bookings?ids=` | Zaliczka, klient, usługodawca i status dla payment-service | int |
| POST | `/internal/bookings/{bookingId}/mark-paid` | Zaliczka wpłacona: „Czeka na zaliczkę” → „Oczekuje” | int |

### Zasady

- Dostępność usługi (oferty typu Usługa) ustawia jej właściciel: `{weekly: [{day 1–7 (0 = niedziela), from "HH:mm", to}], exceptions: [{date, closed}], slotMinutes 15–480 co 15}`. Godziny są w strefie Europe/Warsaw (`rynek.bookings.zone`), więc zmiana czasu ich nie przesuwa. Bez zapisanych reguł obowiązują pon.–pt. 9–17, a slot to czas trwania usługi z katalogu. Slot jest zarazem czasem jednej rezerwacji.
- `GET /slots?from=&to=` (ISO 8601, najwyżej 62 dni, domyślnie 14 dni od teraz) zwraca sloty wolne i zajęte (`{start, end, free}`). Slot jest zajęty, gdy nachodzi na aktywną rezerwację. Przeszłość i dni zamknięte są pomijane. `next-slot` szuka w ciągu 60 dni. Obie ścieżki są publiczne, tylko dla opublikowanych usług z cennikiem (inaczej 404).
- `POST /bookings {offerId, start, address, notes?}` z `Idempotency-Key`: `start` musi być wolnym slotem (inaczej 409), a własnej usługi nie można zarezerwować. Indeks w bazie nie pozwala zająć jednego slotu dwa razy. Cena szacunkowa to cena „od” razy czas usługi w godzinach (jednostka „godz.”), w innych jednostkach cena „od”. Zaliczka to procent z cennika. Tytuł, kanał, imię klienta (identity) i ceny są kopią z chwili rezerwacji.
- Statusy: „Czeka na zaliczkę” (z zaliczką, przetrzymanie `rynek.bookings.deposit-hold` = 10 min) → (wpłata) „Oczekuje” → „Potwierdzona” (`accept`) albo „Inny termin” (`propose-time`, potem klient `accept-proposal` → „Potwierdzona”) → „Wykonana” (`complete`, po terminie usługi; `rynek.bookings.complete-early=true` tylko do testów). Bez zaliczki rezerwacja od razu „Oczekuje”. Nieopłacone przetrzymanie wygasa (sprawdzenie co minutę) i zwalnia termin.
- Anulować może klient i usługodawca (`/cancel`, opcjonalny `reason`), przed terminem potwierdzonej usługi. `decline` to anulowanie przez usługodawcę rezerwacji czekającej na decyzję. Każde anulowanie (także wygaśnięcie) trafia do payment-service (`POST /internal/bookings/{id}/cancelled`): wpłacona zaliczka wraca w całości, a płatność w toku jest nieudana. Gdy payment-service nie odpowiada, zgłoszenie jest ponawiane co minutę (`payment_notified`). Zasady zatrzymania zaliczki przy późnym anulowaniu to decyzja biznesowa, na razie zaliczka zawsze wraca.
- Usługodawca nie widzi rezerwacji czekających na zaliczkę. Jego lista jest od najbliższego terminu (`from` filtruje), a lista klienta od najpóźniejszego.
- `calendar.ics`: wydarzenie iCalendar (UTC) dla klienta i usługodawcy.
- `Booking` ma więcej pól niż kształt z frontendu: `end`, `requestedStart` (termin klienta przy propozycji), `holdUntil`, `cancelReason`, `paymentId`.

## Zdarzenia Kafka

Wiersze z §17, w których występuje `booking` (publikuje lub słucha).

| Zdarzenie | Publikuje | Słuchają |
|---|---|---|
| `payment.succeeded`, `payment.failed`, `payment.refunded` | payment | order, booking, notification |
| `booking.created`, `booking.confirmed`, `booking.cancelled`, `booking.completed` | booking | notification, analytics, engagement |

## Dane i zależności

- **Magazyn danych:** PostgreSQL (baza `bookings`, Flyway)
- **Zależności:** catalog-service (`GET /internal/offers/{id}/price-and-stock` z cennikiem usługi), channel-service (nagłówek usługodawcy), identity-service (imię klienta), payment-service (`POST /internal/bookings/{id}/cancelled`); payment-service woła `/internal/bookings/**`
- **Kontrakty:** `rynek-contracts/openapi/` i `rynek-contracts/events/`
- **Lokalne uruchomienie:** `rynek-infra/local/`

## Kolejność budowy

Etap z §19: 6. `booking-service` — usługi.

## Wyjątki routingu

Gateway (§2) kieruje ruch po prefiksie ścieżki, a poniższe endpointy leżą pod prefiksem innego serwisu. Gateway obsługuje je trasami szczegółowymi (`order: -1`), które mają pierwszeństwo przed trasami po prefiksie. Ścieżki pozostają bez zmian (decyzja z 2026-10-24). Test `RoutingTests` w `rynek-api-gateway` sprawdza, czy każda z nich trafia do właściwego serwisu.

- `/api/v1/bookings/{bookingId}/review`: należy do `engagement-service` (prefiks wskazuje `booking-service`).

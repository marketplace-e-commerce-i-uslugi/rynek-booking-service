-- Reguły dostępności usługi (jednej oferty typu Usługa): długość slotu, godziny pracy w tygodniu i dni zamknięte.
-- Brak wiersza = domyślne godziny (pon.–pt. 9–17).
CREATE TABLE availability (
    offer_id     uuid PRIMARY KEY,
    provider_id  uuid        NOT NULL,
    slot_minutes int         NOT NULL CHECK (slot_minutes BETWEEN 15 AND 480),
    updated_at   timestamptz NOT NULL
);

-- day: 1 = poniedziałek … 7 = niedziela; godziny w strefie Europe/Warsaw.
CREATE TABLE availability_hours (
    offer_id  uuid NOT NULL REFERENCES availability (offer_id) ON DELETE CASCADE,
    position  int  NOT NULL,
    day       int  NOT NULL CHECK (day BETWEEN 1 AND 7),
    from_time time NOT NULL,
    to_time   time NOT NULL,
    PRIMARY KEY (offer_id, position)
);

CREATE TABLE availability_exceptions (
    offer_id uuid    NOT NULL REFERENCES availability (offer_id) ON DELETE CASCADE,
    position int     NOT NULL,
    day      date    NOT NULL,
    closed   boolean NOT NULL,
    PRIMARY KEY (offer_id, position)
);

-- Rezerwacja terminu usługi. Tytuł, kanał, klient i ceny są kopią z chwili rezerwacji.
CREATE TABLE bookings (
    id                uuid PRIMARY KEY,
    offer_id          uuid         NOT NULL,
    offer_title       varchar(200) NOT NULL,
    channel_id        uuid         NOT NULL,
    provider_id       uuid         NOT NULL,
    customer_id       uuid         NOT NULL,
    customer_name     varchar(120) NOT NULL,
    start_at          timestamptz  NOT NULL,
    end_at            timestamptz  NOT NULL,
    -- Termin wybrany przez klienta, gdy usługodawca zaproponował inny.
    requested_start   timestamptz,
    address           varchar(250) NOT NULL,
    notes             varchar(1000),
    status            varchar(20)  NOT NULL
        CHECK (status IN ('AWAITING_DEPOSIT', 'REQUESTED', 'PROPOSED', 'CONFIRMED', 'CANCELLED', 'COMPLETED')),
    estimated_price   bigint       NOT NULL CHECK (estimated_price >= 0),
    deposit           bigint       NOT NULL CHECK (deposit >= 0),
    -- Do kiedy termin czeka na zaliczkę (AWAITING_DEPOSIT).
    hold_until        timestamptz,
    payment_id        uuid,
    cancel_reason     varchar(300),
    cancelled_by      varchar(10)  CHECK (cancelled_by IN ('CUSTOMER', 'PROVIDER', 'SYSTEM')),
    -- false: anulowanie czeka na przyjęcie przez payment-service (zwrot zaliczki, zamknięcie płatności w toku).
    payment_notified  boolean      NOT NULL DEFAULT true,
    idempotency_key   varchar(100) NOT NULL,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    version           int          NOT NULL DEFAULT 0,
    UNIQUE (customer_id, idempotency_key)
);

-- Jeden aktywny termin na slot: dwie osoby nie zarezerwują tej samej godziny.
CREATE UNIQUE INDEX bookings_slot_taken_idx ON bookings (offer_id, start_at)
    WHERE status IN ('AWAITING_DEPOSIT', 'REQUESTED', 'PROPOSED', 'CONFIRMED');
CREATE INDEX bookings_offer_active_idx ON bookings (offer_id, start_at)
    WHERE status IN ('AWAITING_DEPOSIT', 'REQUESTED', 'PROPOSED', 'CONFIRMED');
CREATE INDEX bookings_customer_idx ON bookings (customer_id, start_at DESC);
CREATE INDEX bookings_provider_idx ON bookings (provider_id, start_at);
CREATE INDEX bookings_hold_idx ON bookings (hold_until) WHERE status = 'AWAITING_DEPOSIT';
CREATE INDEX bookings_payment_unnotified_idx ON bookings (updated_at) WHERE NOT payment_notified;

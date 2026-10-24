package pl.rynek.booking.api.dto;

/**
 * Ciało żądania: Rezerwacja slotu z krótkim przetrzymaniem (np. 10 min), adres i uwagi (Idempotency-Key). Pola do uzupełnienia razem z kontraktem w rynek-contracts.
 */
public record CreateBookingRequest() {
}

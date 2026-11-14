package pl.rynek.booking.api.dto;

import java.time.Instant;
import java.util.UUID;

/** Sprzedaż dla analytics-service: wykonana usługa; {@code at} to jej termin, {@code amount} cena w groszach. */
public record SaleResponse(UUID id, Instant at, long amount, UUID offerId) {
}

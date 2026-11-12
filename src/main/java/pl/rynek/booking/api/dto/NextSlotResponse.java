package pl.rynek.booking.api.dto;

import java.time.Instant;

/** {@code start} jest pusty, gdy w najbliższych 60 dniach nie ma wolnego terminu. */
public record NextSlotResponse(Instant start) {
}

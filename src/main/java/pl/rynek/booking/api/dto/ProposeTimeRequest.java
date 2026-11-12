package pl.rynek.booking.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/** Nowy początek usługi; czas trwania zostaje ten sam. */
public record ProposeTimeRequest(@NotNull(message = "Podaj nowy termin.") Instant start) {
}

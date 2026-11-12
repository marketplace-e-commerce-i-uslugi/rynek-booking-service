package pl.rynek.booking.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Kształt {@code CreateBookingRequest} z rynek-frontend: usługa, początek wolnego slotu, adres i uwagi. */
public record CreateBookingRequest(
        @NotNull(message = "Podaj usługę.") UUID offerId,
        @NotNull(message = "Wybierz termin w kalendarzu.") Instant start,
        @NotBlank(message = "Podaj adres wykonania usługi.") @Size(max = 250, message = "Adres może mieć najwyżej 250 znaków.") String address,
        @Size(max = 1000, message = "Uwagi mogą mieć najwyżej 1000 znaków.") String notes) {
}

package pl.rynek.booking.api.dto;

import jakarta.validation.constraints.Size;

/** Powód anulowania jest opcjonalny; bez niego druga strona widzi „Klient anulował rezerwację.” albo podobny. */
public record CancelBookingRequest(@Size(max = 300, message = "Powód może mieć najwyżej 300 znaków.") String reason) {
}

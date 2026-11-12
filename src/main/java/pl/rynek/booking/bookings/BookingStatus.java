package pl.rynek.booking.bookings;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * W API polskie etykiety ({@code BookingStatus} z rynek-frontend plus „Czeka na zaliczkę”), w bazie nazwy stałych.
 * <ul>
 *   <li>{@code AWAITING_DEPOSIT}: termin przetrzymany na wpłatę zaliczki (np. 10 minut), potem wygasa.</li>
 *   <li>{@code REQUESTED}: czeka na decyzję usługodawcy.</li>
 *   <li>{@code PROPOSED}: usługodawca zaproponował inny termin, czeka na klienta.</li>
 *   <li>{@code CONFIRMED}, {@code CANCELLED}, {@code COMPLETED}.</li>
 * </ul>
 */
public enum BookingStatus {

    AWAITING_DEPOSIT("Czeka na zaliczkę"),
    REQUESTED("Oczekuje"),
    PROPOSED("Inny termin"),
    CONFIRMED("Potwierdzona"),
    CANCELLED("Anulowana"),
    COMPLETED("Wykonana");

    /** Statusy, które zajmują termin w kalendarzu usługodawcy. */
    public static final Set<BookingStatus> ACTIVE = EnumSet.of(AWAITING_DEPOSIT, REQUESTED, PROPOSED, CONFIRMED);

    private final String label;

    BookingStatus(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }

    /** Filtr {@code ?status=} przyjmuje etykietę (tak wysyła frontend) albo nazwę stałej. */
    public static Optional<BookingStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String v = value.trim();
        return Arrays.stream(values()).filter(s -> s.label.equalsIgnoreCase(v) || s.name().equalsIgnoreCase(v)).findFirst();
    }
}

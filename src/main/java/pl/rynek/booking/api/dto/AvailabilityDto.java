package pl.rynek.booking.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import pl.rynek.booking.availability.Rules;

/**
 * Kształt {@code Availability} z rynek-frontend: godziny pracy ({@code day}: 1 = poniedziałek … 7 = niedziela, 0 też
 * oznacza niedzielę; {@code from}/{@code to} jako „HH:mm”, strefa Europe/Warsaw), dni zamknięte i długość slotu.
 */
public record AvailabilityDto(List<Weekly> weekly, List<Exception> exceptions,
                              @NotNull(message = "Podaj długość slotu.") Integer slotMinutes) {

    public record Weekly(int day, @JsonFormat(pattern = "HH:mm") LocalTime from, @JsonFormat(pattern = "HH:mm") LocalTime to) {
    }

    public record Exception(LocalDate date, boolean closed) {
    }

    public Rules toRules() {
        return new Rules(weekly == null ? List.of() : weekly.stream().map(w -> new Rules.Hours(w.day(), w.from(), w.to())).toList(),
                exceptions == null ? List.of() : exceptions.stream().map(e -> new Rules.Closed(e.date(), e.closed())).toList(),
                slotMinutes);
    }

    public static AvailabilityDto of(Rules rules) {
        return new AvailabilityDto(rules.weekly().stream().map(h -> new Weekly(h.day(), h.from(), h.to())).toList(),
                rules.exceptions().stream().map(e -> new Exception(e.date(), e.closed())).toList(), rules.slotMinutes());
    }
}

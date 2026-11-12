package pl.rynek.booking.api.dto;

import java.time.Instant;

import pl.rynek.booking.availability.SlotCalendar;

/** Kształt {@code Slot} z rynek-frontend plus {@code end}. */
public record SlotResponse(Instant start, Instant end, boolean free) {

    public static SlotResponse of(SlotCalendar.Slot s) {
        return new SlotResponse(s.start(), s.end(), s.free());
    }
}

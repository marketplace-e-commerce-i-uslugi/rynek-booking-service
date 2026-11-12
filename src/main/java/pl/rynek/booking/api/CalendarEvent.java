package pl.rynek.booking.api;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import pl.rynek.booking.bookings.Booking;

/** Wydarzenie iCalendar (RFC 5545) dla rezerwacji: czas w UTC, wiersze CRLF, tekst z ucieczką znaków specjalnych. */
final class CalendarEvent {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private CalendarEvent() {
    }

    static String of(Booking b, String providerName, Instant now) {
        String description = "Rezerwacja w Rynku u " + providerName + "." + (b.getNotes() == null ? "" : " Uwagi: " + b.getNotes());
        return String.join("\r\n",
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Rynek//Rezerwacje//PL",
                "CALSCALE:GREGORIAN",
                "BEGIN:VEVENT",
                "UID:" + b.getId() + "@rynek",
                "DTSTAMP:" + UTC.format(now),
                "DTSTART:" + UTC.format(b.getStart()),
                "DTEND:" + UTC.format(b.getEnd()),
                "SUMMARY:" + text(b.getOfferTitle()),
                "LOCATION:" + text(b.getAddress()),
                "DESCRIPTION:" + text(description),
                "END:VEVENT",
                "END:VCALENDAR",
                "");
    }

    private static String text(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n");
    }
}

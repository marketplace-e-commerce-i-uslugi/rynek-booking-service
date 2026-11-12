package pl.rynek.booking.availability;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sloty z reguł dostępności: w każdym oknie godzin pracy co {@code slotMinutes}, o ile cały slot mieści się w oknie.
 * Godziny są w strefie usługodawcy (Europe/Warsaw), więc zmiana czasu letniego nie przesuwa godzin pracy. Slot jest
 * zajęty, gdy nachodzi na aktywną rezerwację. Sloty z przeszłości i w dni zamknięte są pomijane.
 */
public final class SlotCalendar {

    private SlotCalendar() {
    }

    public record Slot(Instant start, Instant end, boolean free) {
    }

    /** Przedział zajęty przez aktywną rezerwację. */
    public record Busy(Instant start, Instant end) {

        boolean overlaps(Instant otherStart, Instant otherEnd) {
            return start.isBefore(otherEnd) && otherStart.isBefore(end);
        }
    }

    public static List<Slot> slots(Rules rules, ZoneId zone, Instant from, Instant to, Instant now, List<Busy> busy) {
        Set<LocalDate> closed = rules.exceptions().stream().filter(Rules.Closed::closed).map(Rules.Closed::date).collect(Collectors.toSet());
        List<Slot> slots = new ArrayList<>();
        LocalDate last = to.atZone(zone).toLocalDate();
        for (LocalDate day = from.atZone(zone).toLocalDate(); !day.isAfter(last); day = day.plusDays(1)) {
            if (closed.contains(day)) {
                continue;
            }
            int dow = day.getDayOfWeek().getValue();
            for (Rules.Hours window : rules.weekly()) {
                if (window.day() != dow) {
                    continue;
                }
                int open = window.from().toSecondOfDay() / 60;
                int close = window.to().toSecondOfDay() / 60;
                for (int minute = open; minute + rules.slotMinutes() <= close; minute += rules.slotMinutes()) {
                    Instant start = ZonedDateTime.of(day, LocalTime.ofSecondOfDay(minute * 60L), zone).toInstant();
                    Instant end = start.plusSeconds(rules.slotMinutes() * 60L);
                    if (start.isBefore(from) || start.isAfter(to) || !start.isAfter(now)) {
                        continue;
                    }
                    boolean free = busy.stream().noneMatch(b -> b.overlaps(start, end));
                    slots.add(new Slot(start, end, free));
                }
            }
        }
        slots.sort((a, b) -> a.start().compareTo(b.start()));
        return slots;
    }
}

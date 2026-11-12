package pl.rynek.booking.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SlotCalendarTests {

    static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    @Test
    void slotsFitInsideWorkingHoursAndRespectClosedDaysAndBookings() {
        // Środa 2026-11-18: 9:00–12:30, slot 60 min → 9, 10, 11 (11:30 by się nie zmieścił).
        Rules rules = new Rules(List.of(new Rules.Hours(3, LocalTime.of(9, 0), LocalTime.of(12, 30))),
                List.of(new Rules.Closed(LocalDate.of(2026, 11, 25), true)), 60);
        Instant from = Instant.parse("2026-11-16T00:00:00Z");
        Instant to = Instant.parse("2026-11-29T00:00:00Z");
        List<SlotCalendar.Busy> busy = List.of(new SlotCalendar.Busy(Instant.parse("2026-11-18T09:30:00Z"), Instant.parse("2026-11-18T10:30:00Z")));

        List<SlotCalendar.Slot> slots = SlotCalendar.slots(rules, WARSAW, from, to, LONG_AGO, busy);

        // Listopad: Warszawa = UTC+1. Druga środa (25.11) jest zamknięta.
        assertThat(slots).extracting(SlotCalendar.Slot::start).containsExactly(
                Instant.parse("2026-11-18T08:00:00Z"), Instant.parse("2026-11-18T09:00:00Z"), Instant.parse("2026-11-18T10:00:00Z"));
        // 10:30–11:30 czasu polskiego (9:30–10:30 UTC) nachodzi na sloty 10:00 i 11:00.
        assertThat(slots).extracting(SlotCalendar.Slot::free).containsExactly(true, false, false);
    }

    @Test
    void workingHoursStayTheSameAcrossDaylightSavingChange() {
        Rules rules = new Rules(List.of(new Rules.Hours(7, LocalTime.of(9, 0), LocalTime.of(10, 0)),
                new Rules.Hours(1, LocalTime.of(9, 0), LocalTime.of(10, 0))), List.of(), 60);
        // Zmiana czasu na letni: niedziela 2026-03-29. W niedzielę 9:00 = 07:00 UTC, w sobotę przed nią byłoby 08:00 UTC.
        List<SlotCalendar.Slot> slots = SlotCalendar.slots(rules, WARSAW, Instant.parse("2026-03-28T00:00:00Z"),
                Instant.parse("2026-03-31T00:00:00Z"), LONG_AGO, List.of());
        assertThat(slots).extracting(SlotCalendar.Slot::start)
                .containsExactly(Instant.parse("2026-03-29T07:00:00Z"), Instant.parse("2026-03-30T07:00:00Z"));
    }

    @Test
    void pastSlotsAreSkipped() {
        Rules rules = new Rules(List.of(new Rules.Hours(3, LocalTime.of(9, 0), LocalTime.of(12, 0))), List.of(), 60);
        Instant now = Instant.parse("2026-11-18T09:15:00Z");
        assertThat(SlotCalendar.slots(rules, WARSAW, Instant.parse("2026-11-18T00:00:00Z"), Instant.parse("2026-11-19T00:00:00Z"), now, List.of()))
                .extracting(SlotCalendar.Slot::start).containsExactly(Instant.parse("2026-11-18T10:00:00Z"));
    }

    @Test
    void validationNormalizesSundayAndRejectsOverlaps() {
        Rules ok = AvailabilityService.validate(new Rules(List.of(new Rules.Hours(0, LocalTime.of(10, 0), LocalTime.of(14, 0))), List.of(), 30));
        assertThat(ok.weekly().getFirst().day()).isEqualTo(7);
        assertThatThrownBy(() -> AvailabilityService.validate(new Rules(List.of(new Rules.Hours(1, LocalTime.of(9, 0), LocalTime.of(13, 0)),
                new Rules.Hours(1, LocalTime.of(12, 0), LocalTime.of(16, 0))), List.of(), 60)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("nie mogą na siebie nachodzić");
        assertThatThrownBy(() -> AvailabilityService.validate(new Rules(List.of(), List.of(), 50)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("co 15 minut");
        assertThatThrownBy(() -> AvailabilityService.validate(new Rules(List.of(new Rules.Hours(2, LocalTime.of(14, 0), LocalTime.of(9, 0))), List.of(), 60)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("po godzinie rozpoczęcia");
    }

    @Test
    void defaultsUseServiceDurationRoundedToQuarterHour() {
        assertThat(Rules.defaults(50).slotMinutes()).isEqualTo(45);
        assertThat(Rules.defaults(5).slotMinutes()).isEqualTo(15);
        assertThat(Rules.defaults(600).slotMinutes()).isEqualTo(480);
        assertThat(Rules.defaults(120).weekly()).hasSize(5);
    }
}

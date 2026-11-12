package pl.rynek.booking.availability;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Reguły dostępności usługi: godziny pracy w dniach tygodnia (1 = poniedziałek … 7 = niedziela), dni zamknięte i długość
 * slotu w minutach. Slot to zarazem czas trwania jednej rezerwacji.
 */
public record Rules(List<Hours> weekly, List<Closed> exceptions, int slotMinutes) {

    public record Hours(int day, LocalTime from, LocalTime to) {
    }

    public record Closed(LocalDate date, boolean closed) {
    }

    /** Domyślne godziny, zanim usługodawca je ustawi: pon.–pt. 9–17, slot = czas trwania usługi (zaokrąglony do 15 min). */
    public static Rules defaults(int serviceDurationMin) {
        int slot = Math.clamp(Math.round(serviceDurationMin / 15f) * 15, 15, 480);
        List<Hours> weekdays = List.of(1, 2, 3, 4, 5).stream().map(d -> new Hours(d, LocalTime.of(9, 0), LocalTime.of(17, 0))).toList();
        return new Rules(weekdays, List.of(), slot);
    }
}

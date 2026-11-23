package pl.rynek.booking.bookings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Rezerwacja z terminem w przeszłości nie może zostać potwierdzona, a przeterminowana czekająca na decyzję da się anulować systemowo. */
class PastBookingTests {

    private final Instant now = Instant.parse("2026-11-23T10:00:00Z");

    private Booking requestedStartingAt(Instant start) {
        Booking.Draft draft = new Booking.Draft(UUID.randomUUID(), "Naprawa", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Klient",
                start, start.plus(Duration.ofHours(1)), "ul. Krótka 1", null, 10000, 0);
        return new Booking(draft, "k", Duration.ofMinutes(10), now.minus(Duration.ofDays(10)));
    }

    @Test
    void providerCannotAcceptPastTerm() {
        Booking b = requestedStartingAt(now.minus(Duration.ofDays(5)));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> b.accept(now));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertEquals(BookingStatus.REQUESTED, b.getStatus());
    }

    @Test
    void futureTermCanBeAccepted() {
        Booking b = requestedStartingAt(now.plus(Duration.ofDays(1)));
        b.accept(now);
        assertEquals(BookingStatus.CONFIRMED, b.getStatus());
    }

    @Test
    void customerCannotAcceptPastProposal() {
        Booking b = requestedStartingAt(now.plus(Duration.ofHours(2)));
        b.propose(now.plus(Duration.ofHours(1)), Duration.ofHours(1), now);
        assertThrows(ResponseStatusException.class, () -> b.acceptProposal(now.plus(Duration.ofHours(3))));
    }

    @Test
    void undecidedPastBookingCanBeCancelledBySystem() {
        Booking b = requestedStartingAt(now.minus(Duration.ofDays(5)));
        b.cancel(Booking.CancelledBy.SYSTEM, "Termin minął.", now);
        assertEquals(BookingStatus.CANCELLED, b.getStatus());
    }
}

package pl.rynek.booking.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.booking.bookings.Booking;
import pl.rynek.booking.bookings.BookingService;

/** Dla payment-service: zaliczka do opłacenia, oznaczenie wpłaty i statusy do salda usługodawcy. Dostęp: int. */
@RestController
public class InternalBookingsController {

    static final int MAX_BATCH = 100;

    private final BookingService bookings;

    public InternalBookingsController(BookingService bookings) {
        this.bookings = bookings;
    }

    /** {@code status} to nazwa stałej (np. {@code AWAITING_DEPOSIT}); {@code providerId} odbiera zaliczkę. */
    @GetMapping("/internal/bookings/{bookingId}")
    public InternalBooking getBooking(@PathVariable UUID bookingId) {
        return InternalBooking.of(bookings.internal(bookingId));
    }

    @GetMapping("/internal/bookings")
    public List<InternalBooking> getBookings(@RequestParam List<UUID> ids) {
        if (ids.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Najwyżej " + MAX_BATCH + " rezerwacji naraz.");
        }
        return bookings.internal(ids).stream().map(InternalBooking::of).toList();
    }

    /** Zaliczka wpłacona: „Czeka na zaliczkę” → „Oczekuje”. Ponowienie nic nie zmienia, anulowana daje 409. */
    @PostMapping("/internal/bookings/{bookingId}/mark-paid")
    public InternalBooking markPaid(@PathVariable UUID bookingId, @RequestBody(required = false) MarkPaidRequest request) {
        return InternalBooking.of(bookings.markPaid(bookingId, request == null ? null : request.paymentId()));
    }

    /** Opinia zapisana w engagement-service: klient nie dostaje już „Wystaw opinię”. Dostęp: int. */
    @PutMapping("/internal/bookings/{bookingId}/reviewed")
    public ResponseEntity<Void> reviewed(@PathVariable UUID bookingId) {
        bookings.markReviewed(bookingId);
        return ResponseEntity.noContent().build();
    }

    public record MarkPaidRequest(UUID paymentId) {
    }

    public record InternalBooking(UUID id, UUID offerId, String title, UUID channelId, UUID customerId, UUID providerId, String status, long deposit) {

        static InternalBooking of(Booking b) {
            return new InternalBooking(b.getId(), b.getOfferId(), b.getOfferTitle(), b.getChannelId(), b.getCustomerId(), b.getProviderId(), b.getStatus().name(), b.getDeposit());
        }
    }
}

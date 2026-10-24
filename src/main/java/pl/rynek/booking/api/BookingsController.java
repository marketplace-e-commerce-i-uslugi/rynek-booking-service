package pl.rynek.booking.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.booking.api.dto.CancelBookingRequest;
import pl.rynek.booking.api.dto.CreateBookingRequest;

import static pl.rynek.booking.common.NotImplemented.notImplemented;

@RestController
public class BookingsController {

    /**
     * Rezerwacja slotu z krótkim przetrzymaniem (np. 10 min), adres i uwagi (Idempotency-Key). Dostęp: user.
     */
    @PostMapping("/api/v1/bookings")
    public void createBooking(@RequestBody CreateBookingRequest request) {
        throw notImplemented();
    }

    /**
     * Moje rezerwacje. Dostęp: user.
     */
    @GetMapping("/api/v1/bookings/mine")
    public void listMyBookings(@RequestParam(required = false) String status) {
        throw notImplemented();
    }

    /**
     * Szczegóły. Dostęp: user / seller.
     */
    @GetMapping("/api/v1/bookings/{bookingId}")
    public void getBooking(@PathVariable String bookingId) {
        throw notImplemented();
    }

    /**
     * Anulowanie. Dostęp: user / seller.
     */
    @PostMapping("/api/v1/bookings/{bookingId}/cancel")
    public void cancelBooking(@PathVariable String bookingId, @RequestBody CancelBookingRequest request) {
        throw notImplemented();
    }

    /**
     * Wydarzenie do kalendarza. Dostęp: user.
     */
    @GetMapping("/api/v1/bookings/{bookingId}/calendar.ics")
    public void getCalendarEvent(@PathVariable String bookingId) {
        throw notImplemented();
    }
}

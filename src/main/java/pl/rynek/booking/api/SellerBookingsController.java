package pl.rynek.booking.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.booking.api.dto.ProposeTimeRequest;

import static pl.rynek.booking.common.NotImplemented.notImplemented;

@RestController
public class SellerBookingsController {

    /**
     * Rezerwacje usługodawcy. Dostęp: seller.
     */
    @GetMapping("/api/v1/seller/bookings")
    public void listBookings(@RequestParam(required = false) String status, @RequestParam(required = false) String from) {
        throw notImplemented();
    }

    /**
     * Akceptacja. Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/bookings/{bookingId}/accept")
    public void accept(@PathVariable String bookingId) {
        throw notImplemented();
    }

    /**
     * Odrzucenie. Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/bookings/{bookingId}/decline")
    public void decline(@PathVariable String bookingId) {
        throw notImplemented();
    }

    /**
     * Propozycja innego terminu. Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/bookings/{bookingId}/propose-time")
    public void proposeTime(@PathVariable String bookingId, @RequestBody ProposeTimeRequest request) {
        throw notImplemented();
    }

    /**
     * Oznaczenie jako wykonane (uruchamia prośbę o opinię). Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/bookings/{bookingId}/complete")
    public void complete(@PathVariable String bookingId) {
        throw notImplemented();
    }
}

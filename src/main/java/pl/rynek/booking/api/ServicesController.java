package pl.rynek.booking.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.booking.api.dto.SetAvailabilityRequest;

import static pl.rynek.booking.common.NotImplemented.notImplemented;

@RestController
public class ServicesController {

    /**
     * Reguły dostępności usługodawcy. Dostęp: seller.
     */
    @GetMapping("/api/v1/services/{offerId}/availability")
    public void getAvailability(@PathVariable String offerId) {
        throw notImplemented();
    }

    /**
     * Godziny pracy, wyjątki, urlopy, czas trwania slotu. Dostęp: seller.
     */
    @PutMapping("/api/v1/services/{offerId}/availability")
    public void setAvailability(@PathVariable String offerId, @RequestBody SetAvailabilityRequest request) {
        throw notImplemented();
    }

    /**
     * Wolne sloty (kalendarz i godziny). Dostęp: pub.
     */
    @GetMapping("/api/v1/services/{offerId}/slots")
    public void listFreeSlots(@PathVariable String offerId, @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        throw notImplemented();
    }

    /**
     * Najbliższy termin (karta „Najbliższy termin”). Dostęp: pub.
     */
    @GetMapping("/api/v1/services/{offerId}/next-slot")
    public void getNextSlot(@PathVariable String offerId) {
        throw notImplemented();
    }
}

package pl.rynek.booking.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.booking.availability.AvailabilityService;

/** Dla catalog-service: najbliższe wolne terminy wielu usług naraz (karty ofert). Dostęp: int. */
@RestController
public class InternalServicesController {

    static final int MAX_BATCH = 100;

    private final AvailabilityService availability;

    public InternalServicesController(AvailabilityService availability) {
        this.availability = availability;
    }

    /**
     * Mapa {@code offerId → najbliższy wolny termin}. Usługi bez wolnego terminu albo niedostępne do rezerwacji
     * (nieopublikowane, bez cennika) są pominięte.
     */
    @GetMapping("/internal/services/next-slots")
    public Map<UUID, Instant> nextSlots(@RequestParam List<UUID> ids) {
        if (ids.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Najwyżej " + MAX_BATCH + " usług naraz.");
        }
        Map<UUID, Instant> found = new LinkedHashMap<>();
        for (UUID id : ids.stream().distinct().toList()) {
            try {
                availability.nextSlot(id).ifPresent(start -> found.put(id, start));
            } catch (ResponseStatusException e) {
                // Usługa nie przyjmuje rezerwacji: bez terminu, reszta listy się nie psuje.
            }
        }
        return found;
    }
}

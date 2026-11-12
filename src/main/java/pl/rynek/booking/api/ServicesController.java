package pl.rynek.booking.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.booking.api.dto.AvailabilityDto;
import pl.rynek.booking.api.dto.NextSlotResponse;
import pl.rynek.booking.api.dto.SlotResponse;
import pl.rynek.booking.availability.AvailabilityService;

@RestController
public class ServicesController {

    private final AvailabilityService availability;
    private final Clock clock;

    public ServicesController(AvailabilityService availability, Clock clock) {
        this.availability = availability;
        this.clock = clock;
    }

    /**
     * Reguły dostępności usługodawcy (zapisane albo domyślne pon.–pt. 9–17). Dostęp: seller (właściciel usługi).
     */
    @GetMapping("/api/v1/services/{offerId}/availability")
    public AvailabilityDto getAvailability(@AuthenticationPrincipal Jwt token, @PathVariable UUID offerId) {
        return AvailabilityDto.of(availability.get(userId(token), offerId));
    }

    /**
     * Godziny pracy, dni zamknięte i długość slotu (zarazem czas jednej rezerwacji). Dostęp: seller.
     */
    @PutMapping("/api/v1/services/{offerId}/availability")
    public AvailabilityDto setAvailability(@AuthenticationPrincipal Jwt token, @PathVariable UUID offerId,
                                           @Valid @RequestBody AvailabilityDto request) {
        return AvailabilityDto.of(availability.set(userId(token), offerId, request.toRules()));
    }

    /**
     * Sloty (wolne i zajęte) w przedziale {@code from}–{@code to} (ISO 8601, najwyżej 62 dni; domyślnie 14 dni od teraz).
     * Dostęp: pub.
     */
    @GetMapping("/api/v1/services/{offerId}/slots")
    public List<SlotResponse> listFreeSlots(@PathVariable UUID offerId, @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        Instant start = from == null || from.isBlank() ? clock.instant() : instant(from);
        Instant end = to == null || to.isBlank() ? start.plus(Duration.ofDays(14)) : instant(to);
        return availability.slots(offerId, start, end).stream().map(SlotResponse::of).toList();
    }

    /**
     * Najbliższy wolny termin (karta „Najbliższy termin”), pusty, gdy w ciągu 60 dni nie ma wolnego. Dostęp: pub.
     */
    @GetMapping("/api/v1/services/{offerId}/next-slot")
    public NextSlotResponse getNextSlot(@PathVariable UUID offerId) {
        return new NextSlotResponse(availability.nextSlot(offerId).orElse(null));
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Daty podaj w formacie ISO 8601, np. 2026-11-20T09:00:00Z.");
        }
    }

    private static UUID userId(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}

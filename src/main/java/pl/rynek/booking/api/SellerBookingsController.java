package pl.rynek.booking.api;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.booking.api.dto.BookingResponse;
import pl.rynek.booking.api.dto.CursorPage;
import pl.rynek.booking.api.dto.ProposeTimeRequest;
import pl.rynek.booking.bookings.BookingService;
import pl.rynek.booking.bookings.BookingStatus;

/** Usługodawca to właściciel usługi ({@code provider_id} = sub z JWT). */
@RestController
public class SellerBookingsController {

    private final BookingService bookings;
    private final BookingViews views;

    public SellerBookingsController(BookingService bookings, BookingViews views) {
        this.bookings = bookings;
        this.views = views;
    }

    /**
     * Rezerwacje usługodawcy od najbliższego terminu ({@code from}: ISO 8601), bez czekających na zaliczkę. Dostęp: seller.
     */
    @GetMapping("/api/v1/seller/bookings")
    public CursorPage<BookingResponse> listBookings(@AuthenticationPrincipal Jwt token, @RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String from, @RequestParam(required = false) String cursor,
                                                    @RequestParam(required = false) Integer limit) {
        BookingStatus filter = BookingViews.status(status);
        Instant since = from == null || from.isBlank() ? null : instant(from);
        UUID provider = userId(token);
        return views.page(cursor, limit, (offset, n) -> bookings.ofProvider(provider, filter, since, offset, n));
    }

    /**
     * Akceptacja: „Oczekuje” → „Potwierdzona”. Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/bookings/{bookingId}/accept")
    public BookingResponse accept(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
        return views.one(bookings.accept(userId(token), bookingId));
    }

    /**
     * Odrzucenie rezerwacji czekającej na decyzję; zaliczka wraca do klienta. Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/bookings/{bookingId}/decline")
    public BookingResponse decline(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
        return views.one(bookings.decline(userId(token), bookingId));
    }

    /**
     * Propozycja innego terminu (ten sam czas trwania): „Inny termin”, klient przyjmuje albo anuluje. Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/bookings/{bookingId}/propose-time")
    public BookingResponse proposeTime(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId,
                                       @Valid @RequestBody ProposeTimeRequest request) {
        return views.one(bookings.propose(userId(token), bookingId, request.start()));
    }

    /**
     * Oznaczenie jako wykonane po terminie usługi: zaliczka staje się dostępna w saldzie, a klient może wystawić opinię.
     * Dostęp: seller.
     */
    @PostMapping("/api/v1/seller/bookings/{bookingId}/complete")
    public BookingResponse complete(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
        return views.one(bookings.complete(userId(token), bookingId));
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datę podaj w formacie ISO 8601, np. 2026-11-20T00:00:00Z.");
        }
    }

    private static UUID userId(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}

package pl.rynek.booking.api;

import java.time.Clock;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.rynek.booking.api.dto.BookingResponse;
import pl.rynek.booking.api.dto.CancelBookingRequest;
import pl.rynek.booking.api.dto.CreateBookingRequest;
import pl.rynek.booking.api.dto.CursorPage;
import pl.rynek.booking.bookings.Booking;
import pl.rynek.booking.bookings.BookingService;
import pl.rynek.booking.bookings.BookingStatus;

@RestController
public class BookingsController {

    private final BookingService bookings;
    private final BookingViews views;
    private final Clock clock;

    public BookingsController(BookingService bookings, BookingViews views, Clock clock) {
        this.bookings = bookings;
        this.views = views;
        this.clock = clock;
    }

    /**
     * Rezerwacja wolnego slotu ({@code Idempotency-Key}). Z zaliczką termin czeka na wpłatę 10 minut („Czeka na zaliczkę”),
     * bez zaliczki od razu czeka na decyzję usługodawcy („Oczekuje”). Dostęp: user.
     */
    @PostMapping("/api/v1/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createBooking(@AuthenticationPrincipal Jwt token,
                                         @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                         @Valid @RequestBody CreateBookingRequest request) {
        return views.one(bookings.create(userId(token), idempotencyKey,
                new BookingService.Request(request.offerId(), request.start(), request.address(), request.notes())));
    }

    /**
     * Moje rezerwacje, od najpóźniejszego terminu. Dostęp: user.
     */
    @GetMapping("/api/v1/bookings/mine")
    public CursorPage<BookingResponse> listMyBookings(@AuthenticationPrincipal Jwt token, @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        BookingStatus filter = BookingViews.status(status);
        UUID customer = userId(token);
        return views.page(cursor, limit, (offset, n) -> bookings.ofCustomer(customer, filter, offset, n));
    }

    /**
     * Szczegóły. Dostęp: user / seller (klient i usługodawca).
     */
    @GetMapping("/api/v1/bookings/{bookingId}")
    public BookingResponse getBooking(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
        return views.one(bookings.visible(userId(token), bookingId));
    }

    /**
     * Anulowanie przed terminem przez klienta albo usługodawcę; wpłacona zaliczka wraca do klienta. Ciało ({@code reason})
     * jest opcjonalne. Dostęp: user / seller.
     */
    @PostMapping("/api/v1/bookings/{bookingId}/cancel")
    public BookingResponse cancelBooking(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId,
                                         @Valid @RequestBody(required = false) CancelBookingRequest request) {
        return views.one(bookings.cancel(userId(token), bookingId, request == null ? null : request.reason()));
    }

    /**
     * Usługodawca zaproponował inny termin, a klient go przyjmuje: „Inny termin” → „Potwierdzona”. Dostęp: user (klient).
     * Endpoint spoza dokumentu architektury: bez niego propozycja terminu nie ma odpowiedzi.
     */
    @PostMapping("/api/v1/bookings/{bookingId}/accept-proposal")
    public BookingResponse acceptProposal(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
        return views.one(bookings.acceptProposal(userId(token), bookingId));
    }

    /**
     * Wydarzenie do kalendarza (iCalendar). Dostęp: user / seller.
     */
    @GetMapping("/api/v1/bookings/{bookingId}/calendar.ics")
    public ResponseEntity<String> getCalendarEvent(@AuthenticationPrincipal Jwt token, @PathVariable UUID bookingId) {
        Booking booking = bookings.visible(userId(token), bookingId);
        String provider = views.one(booking).channel().name();
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "calendar", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("rezerwacja.ics").build().toString())
                .body(CalendarEvent.of(booking, provider, clock.instant()));
    }

    private static UUID userId(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}

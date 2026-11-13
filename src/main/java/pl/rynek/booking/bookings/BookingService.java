package pl.rynek.booking.bookings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.booking.availability.AvailabilityService;
import pl.rynek.booking.availability.Rules;
import pl.rynek.booking.availability.SlotCalendar;
import pl.rynek.booking.bookings.Booking.CancelledBy;
import pl.rynek.booking.clients.CatalogClient.OfferInfo;
import pl.rynek.booking.clients.IdentityClient;
import pl.rynek.booking.clients.PaymentClient;

/**
 * Rezerwacje usług. Termin musi być wolnym slotem z reguł dostępności, a indeks w bazie nie pozwala zająć jednego slotu
 * dwa razy. Rezerwacja z zaliczką czeka na wpłatę {@code rynek.bookings.deposit-hold} (10 min), potem wygasa. Każde
 * anulowanie trafia do payment-service (zwrot zaliczki, zamknięcie płatności w toku), z ponawianiem co minutę.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final AvailabilityService availability;
    private final IdentityClient identity;
    private final PaymentClient payment;
    private final BookingNotifications notices;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration depositHold;
    private final boolean completeEarly;

    public BookingService(BookingRepository bookings, AvailabilityService availability, IdentityClient identity, PaymentClient payment,
                          BookingNotifications notices, PlatformTransactionManager transactions, Clock clock,
                          @Value("${rynek.bookings.deposit-hold:10m}") Duration depositHold,
                          @Value("${rynek.bookings.complete-early:false}") boolean completeEarly) {
        this.bookings = bookings;
        this.availability = availability;
        this.identity = identity;
        this.payment = payment;
        this.notices = notices;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
        this.depositHold = depositHold;
        this.completeEarly = completeEarly;
    }

    /** Dane rezerwacji od klienta. */
    public record Request(UUID offerId, Instant start, String address, String notes) {
    }

    /**
     * Rezerwacja wolnego slotu. Ponowienie z tym samym Idempotency-Key zwraca tę samą rezerwację. Szacowana cena: cena
     * „od” razy liczba godzin usługi (jednostka „godz.”), w pozostałych jednostkach cena „od”. Zaliczka to procent z cennika.
     */
    public Booking create(UUID customerId, String idempotencyKey, Request request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Brak nagłówka Idempotency-Key (do 100 znaków).");
        }
        Optional<Booking> earlier = bookings.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        OfferInfo offer = availability.bookable(request.offerId());
        if (offer.ownerId().equals(customerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nie możesz zarezerwować własnej usługi.");
        }
        Rules rules = availability.rulesFor(offer);
        SlotCalendar.Slot slot = availability.slots(offer, rules, request.start(), request.start()).stream()
                .filter(s -> s.start().equals(request.start()) && s.free()).findFirst()
                .orElseThrow(BookingService::slotTaken);
        long estimate = estimate(offer);
        long deposit = Math.round(estimate * offer.service().depositPercent() / 100.0);
        String name = identity.displayName(customerId).orElse("Klient");
        Booking booking = new Booking(new Booking.Draft(offer.offerId(), offer.title(), offer.channelId(), offer.ownerId(), customerId, name,
                slot.start(), slot.end(), request.address().trim(), blankToNull(request.notes()), estimate, deposit),
                idempotencyKey, depositHold, clock.instant());
        Booking saved;
        try {
            saved = transaction.execute(status -> bookings.saveAndFlush(booking));
        } catch (DataIntegrityViolationException e) {
            // Ten sam klucz równolegle albo ktoś właśnie zajął slot.
            return bookings.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey).orElseThrow(BookingService::slotTaken);
        }
        notices.requested(saved);
        return saved;
    }

    static long estimate(OfferInfo offer) {
        long price = offer.price().amount();
        String unit = offer.service().unit() == null ? "" : offer.service().unit().trim().toLowerCase();
        return unit.startsWith("godz") ? Math.round(price * offer.service().durationMin() / 60.0) : price;
    }

    /** Klient i usługodawca widzą rezerwację; dla innych nie istnieje. */
    public Booking visible(UUID userId, UUID bookingId) {
        return find(bookingId, b -> b.getCustomerId().equals(userId) || b.getProviderId().equals(userId));
    }

    public List<Booking> ofCustomer(UUID customerId, BookingStatus status, int offset, int limit) {
        return bookings.findOfCustomer(customerId, status == null ? null : status.name(), offset, limit);
    }

    public List<Booking> ofProvider(UUID providerId, BookingStatus status, Instant from, int offset, int limit) {
        return bookings.findOfProvider(providerId, status == null ? null : status.name(), from, offset, limit);
    }

    /** Anulowanie przez klienta albo usługodawcę (endpoint wspólny). */
    public Booking cancel(UUID userId, UUID bookingId, String reason) {
        Booking b = visible(userId, bookingId);
        CancelledBy by = b.getCustomerId().equals(userId) ? CancelledBy.CUSTOMER : CancelledBy.PROVIDER;
        String why = reason != null && !reason.isBlank() ? reason.trim()
                : by == CancelledBy.CUSTOMER ? "Klient anulował rezerwację." : "Usługodawca anulował rezerwację.";
        return cancelled(bookingId, b2 -> true, by, why);
    }

    public Booking accept(UUID providerId, UUID bookingId) {
        Booking accepted = change(bookingId, provider(providerId), b -> b.accept(clock.instant()));
        notices.accepted(accepted);
        return accepted;
    }

    public Booking decline(UUID providerId, UUID bookingId) {
        Booking b = find(bookingId, provider(providerId));
        if (b.getStatus() != BookingStatus.REQUESTED && b.getStatus() != BookingStatus.PROPOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Odrzucić można rezerwację, która czeka na Twoją decyzję.");
        }
        return cancelled(bookingId, provider(providerId), CancelledBy.PROVIDER, "Usługodawca odrzucił rezerwację.");
    }

    /** Inny termin od usługodawcy: nie może nachodzić na inne aktywne rezerwacje tej usługi. */
    public Booking propose(UUID providerId, UUID bookingId, Instant newStart) {
        Booking b = find(bookingId, provider(providerId));
        Duration length = Duration.between(b.getStart(), b.getEnd());
        boolean overlaps = bookings.findActiveOverlapping(b.getOfferId(), newStart, newStart.plus(length)).stream()
                .anyMatch(other -> !other.getId().equals(bookingId));
        if (overlaps) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "W tym terminie masz już inną rezerwację tej usługi.");
        }
        try {
            Booking proposed = change(bookingId, provider(providerId), x -> x.propose(newStart, length, clock.instant()));
            notices.proposed(proposed);
            return proposed;
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "W tym terminie masz już inną rezerwację tej usługi.");
        }
    }

    public Booking acceptProposal(UUID customerId, UUID bookingId) {
        Booking accepted = change(bookingId, b -> b.getCustomerId().equals(customerId), b -> b.acceptProposal(clock.instant()));
        notices.proposalAccepted(accepted);
        return accepted;
    }

    public Booking complete(UUID providerId, UUID bookingId) {
        Booking completed = change(bookingId, provider(providerId), b -> b.complete(completeEarly, clock.instant()));
        notices.completed(completed);
        return completed;
    }

    /** Zaliczka wpłacona (payment-service). */
    public Booking markPaid(UUID bookingId, UUID paymentId) {
        Booking paid = change(bookingId, b -> true, b -> b.markPaid(paymentId, clock.instant()));
        notices.requested(paid);
        return paid;
    }

    /** engagement-service zapisał opinię o rezerwacji. Ponowienie niczego nie zmienia. */
    public Booking markReviewed(UUID bookingId) {
        return change(bookingId, b -> true, Booking::markReviewed);
    }

    public Booking internal(UUID bookingId) {
        return find(bookingId, b -> true);
    }

    public List<Booking> internal(List<UUID> ids) {
        return bookings.findByIdIn(ids);
    }

    /** Rezerwacje bez wpłaconej zaliczki wygasają i zwalniają termin. Wpłata w międzyczasie wygrywa (blokada wersji). */
    @Scheduled(fixedDelayString = "${rynek.bookings.expiry-check:60s}", initialDelayString = "${rynek.bookings.expiry-check:60s}")
    public void expireHolds() {
        for (UUID id : bookings.findHoldExpired(clock.instant())) {
            try {
                cancelled(id, b -> b.getStatus() == BookingStatus.AWAITING_DEPOSIT, CancelledBy.SYSTEM,
                        "Nie wpłacono zaliczki w ciągu " + depositHold.toMinutes() + " minut.");
                log.info("Rezerwacja {} wygasła bez zaliczki", id);
            } catch (ResponseStatusException e) {
                log.debug("Rezerwacja {} nie wygasła: {}", id, e.getReason());
            }
        }
    }

    @Scheduled(fixedDelayString = "${rynek.bookings.payment-retry:60s}", initialDelayString = "${rynek.bookings.payment-retry:60s}")
    public void notifyPaymentPending() {
        bookings.findPaymentUnnotified().forEach(this::notifyPayment);
    }

    private Booking cancelled(UUID bookingId, Predicate<Booking> allowed, CancelledBy by, String reason) {
        notices.cancelled(change(bookingId, allowed, b -> b.cancel(by, reason, clock.instant())), by);
        return notifyPayment(bookingId);
    }

    /** Odrzucenie 4xx nie jest ponawiane: to błąd danych do ręcznego sprawdzenia, nie chwilowa awaria. */
    private Booking notifyPayment(UUID bookingId) {
        PaymentClient.Result result = payment.bookingCancelled(bookingId);
        if (result == PaymentClient.Result.UNAVAILABLE) {
            log.warn("Rezerwacja {}: payment-service nie odpowiada, ponowimy zgłoszenie anulowania", bookingId);
            return internal(bookingId);
        }
        if (result == PaymentClient.Result.REJECTED) {
            log.error("Rezerwacja {}: payment-service odrzucił zgłoszenie anulowania, sprawdź zaliczkę ręcznie", bookingId);
        }
        try {
            return change(bookingId, b -> true, Booking::paymentNotified);
        } catch (ResponseStatusException e) {
            return internal(bookingId);
        }
    }

    private Booking change(UUID bookingId, Predicate<Booking> allowed, Consumer<Booking> action) {
        try {
            return transaction.execute(status -> {
                Booking b = find(bookingId, allowed);
                action.accept(b);
                return bookings.saveAndFlush(b);
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rezerwacja właśnie się zmieniła. Odśwież i spróbuj ponownie.");
        }
    }

    private Booking find(UUID bookingId, Predicate<Booking> allowed) {
        return bookings.findById(bookingId).filter(allowed)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy tej rezerwacji."));
    }

    private static Predicate<Booking> provider(UUID providerId) {
        return b -> b.getProviderId().equals(providerId);
    }

    private static ResponseStatusException slotTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Ten termin jest już zajęty albo niedostępny. Wybierz inny.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

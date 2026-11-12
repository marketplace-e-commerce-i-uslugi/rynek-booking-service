package pl.rynek.booking.availability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.booking.availability.SlotCalendar.Busy;
import pl.rynek.booking.availability.SlotCalendar.Slot;
import pl.rynek.booking.bookings.BookingRepository;
import pl.rynek.booking.clients.CatalogClient;
import pl.rynek.booking.clients.CatalogClient.OfferInfo;

/**
 * Dostępność usług: reguły ustawia usługodawca (właściciel oferty), a wolne sloty i najbliższy termin widzi każdy. Zanim
 * usługodawca zapisze reguły, obowiązują domyślne (pon.–pt. 9–17).
 */
@Service
public class AvailabilityService {

    static final Duration MAX_RANGE = Duration.ofDays(62);
    static final Duration NEXT_SLOT_HORIZON = Duration.ofDays(60);
    static final int MAX_WINDOWS = 21;
    static final int MAX_EXCEPTIONS = 100;

    private final AvailabilityRepository availability;
    private final BookingRepository bookings;
    private final CatalogClient catalog;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ZoneId zone;

    public AvailabilityService(AvailabilityRepository availability, BookingRepository bookings, CatalogClient catalog,
                               PlatformTransactionManager transactions, Clock clock, @Value("${rynek.bookings.zone}") ZoneId zone) {
        this.availability = availability;
        this.bookings = bookings;
        this.catalog = catalog;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
        this.zone = zone;
    }

    public ZoneId zone() {
        return zone;
    }

    /** Reguły usługi (zapisane albo domyślne). */
    public Rules rulesFor(OfferInfo offer) {
        return availability.findById(offer.offerId()).map(Availability::rules)
                .orElseGet(() -> Rules.defaults(offer.service() == null ? 60 : offer.service().durationMin()));
    }

    /** Reguły dla usługodawcy; inni nie widzą cudzych ustawień (404). */
    public Rules get(UUID sellerId, UUID offerId) {
        return rulesFor(ownService(sellerId, offerId));
    }

    public Rules set(UUID sellerId, UUID offerId, Rules rules) {
        OfferInfo offer = ownService(sellerId, offerId);
        Rules valid = validate(rules);
        return transaction.execute(status -> {
            Availability a = availability.findById(offerId).orElseGet(() -> new Availability(offerId, offer.ownerId()));
            a.replace(valid, clock.instant());
            return availability.saveAndFlush(a).rules();
        });
    }

    /** Sloty usługi w przedziale (najwyżej {@link #MAX_RANGE}), wolne i zajęte, od najwcześniejszego. */
    public List<Slot> slots(UUID offerId, Instant from, Instant to) {
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Koniec przedziału nie może być przed jego początkiem.");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Przedział może mieć najwyżej " + MAX_RANGE.toDays() + " dni.");
        }
        OfferInfo offer = bookable(offerId);
        return slots(offer, rulesFor(offer), from, to);
    }

    /** Najbliższy wolny termin w ciągu {@link #NEXT_SLOT_HORIZON} albo pusty. */
    public Optional<Instant> nextSlot(UUID offerId) {
        OfferInfo offer = bookable(offerId);
        Rules rules = rulesFor(offer);
        Instant from = clock.instant();
        for (Instant chunk = from; chunk.isBefore(from.plus(NEXT_SLOT_HORIZON)); chunk = chunk.plus(Duration.ofDays(14))) {
            Optional<Instant> free = slots(offer, rules, chunk, chunk.plus(Duration.ofDays(14))).stream()
                    .filter(Slot::free).map(Slot::start).findFirst();
            if (free.isPresent()) {
                return free;
            }
        }
        return Optional.empty();
    }

    public List<Slot> slots(OfferInfo offer, Rules rules, Instant from, Instant to) {
        List<Busy> busy = bookings.findActiveOverlapping(offer.offerId(), from, to.plus(Duration.ofMinutes(rules.slotMinutes()))).stream()
                .map(b -> new Busy(b.getStart(), b.getEnd())).toList();
        return SlotCalendar.slots(rules, zone, from, to, clock.instant(), busy);
    }

    /** Usługa, którą można zarezerwować (opublikowana, z cennikiem). */
    public OfferInfo bookable(UUID offerId) {
        return catalog.offer(offerId).filter(OfferInfo::bookable)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ta usługa nie przyjmuje teraz rezerwacji."));
    }

    private OfferInfo ownService(UUID sellerId, UUID offerId) {
        OfferInfo offer = catalog.offer(offerId).filter(o -> o.ownerId().equals(sellerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleźliśmy tej usługi."));
        if (!"SERVICE".equals(offer.type())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Godziny dostępności ustawiasz tylko dla usług.");
        }
        return offer;
    }

    /** Godziny w oknach 15-minutowych, okna tego samego dnia nie nachodzą na siebie, niedziela może przyjść jako 0. */
    static Rules validate(Rules rules) {
        if (rules.slotMinutes() < 15 || rules.slotMinutes() > 480 || rules.slotMinutes() % 15 != 0) {
            throw bad("Długość slotu to od 15 do 480 minut, co 15 minut.");
        }
        List<Rules.Hours> weekly = rules.weekly() == null ? List.of() : rules.weekly();
        List<Rules.Closed> exceptions = rules.exceptions() == null ? List.of() : rules.exceptions();
        if (weekly.size() > MAX_WINDOWS) {
            throw bad("Najwyżej " + MAX_WINDOWS + " przedziałów godzin pracy.");
        }
        if (exceptions.size() > MAX_EXCEPTIONS) {
            throw bad("Najwyżej " + MAX_EXCEPTIONS + " wyjątków w kalendarzu.");
        }
        List<Rules.Hours> normalized = new ArrayList<>();
        for (Rules.Hours h : weekly) {
            int day = h.day() == 0 ? 7 : h.day();
            if (day < 1 || day > 7 || h.from() == null || h.to() == null) {
                throw bad("Podaj dzień tygodnia (1–7) i godziny od–do.");
            }
            if (!h.to().isAfter(h.from())) {
                throw bad("Godzina zakończenia musi być po godzinie rozpoczęcia.");
            }
            normalized.add(new Rules.Hours(day, h.from().withSecond(0).withNano(0), h.to().withSecond(0).withNano(0)));
        }
        normalized.sort(Comparator.comparingInt(Rules.Hours::day).thenComparing(Rules.Hours::from));
        for (int i = 1; i < normalized.size(); i++) {
            Rules.Hours prev = normalized.get(i - 1);
            Rules.Hours cur = normalized.get(i);
            if (prev.day() == cur.day() && cur.from().isBefore(prev.to())) {
                throw bad("Godziny pracy w jednym dniu nie mogą na siebie nachodzić.");
            }
        }
        Set<java.time.LocalDate> seen = new HashSet<>();
        List<Rules.Closed> uniqueExceptions = new ArrayList<>();
        for (Rules.Closed e : exceptions) {
            if (e.date() == null) {
                throw bad("Podaj datę wyjątku.");
            }
            if (seen.add(e.date())) {
                uniqueExceptions.add(e);
            }
        }
        uniqueExceptions.sort(Comparator.comparing(Rules.Closed::date));
        return new Rules(normalized, uniqueExceptions, rules.slotMinutes());
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

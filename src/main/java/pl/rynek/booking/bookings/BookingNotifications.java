package pl.rynek.booking.bookings;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.rynek.booking.bookings.Booking.CancelledBy;
import pl.rynek.booking.clients.NotificationClient;

/**
 * Powiadomienia o rezerwacjach: usługodawca dowiaduje się o nowej (opłaconej) rezerwacji, przyjętym nowym terminie i
 * anulowaniu przez klienta, klient o potwierdzeniu, odrzuceniu, nowym terminie, wykonaniu i wygaśnięciu. Klucz zdarzenia
 * ({@code booking-<id>-<zdarzenie>}) sprawia, że ponowienie nie tworzy drugiego powiadomienia.
 */
@Component
class BookingNotifications {

    private static final String CUSTOMER_LINK = "/profil/rezerwacje";
    private static final String PROVIDER_LINK = "/sprzedawca/rezerwacje";

    private final NotificationClient notifications;
    private final DateTimeFormatter when;

    BookingNotifications(NotificationClient notifications, @Value("${rynek.bookings.zone:Europe/Warsaw}") ZoneId zone) {
        this.notifications = notifications;
        this.when = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("pl-PL")).withZone(zone);
    }

    /** Rezerwacja trafia do usługodawcy dopiero po wpłacie zaliczki (bez zaliczki od razu). */
    void requested(Booking b) {
        if (b.getStatus() == BookingStatus.REQUESTED) {
            toProvider(b, "Nowa rezerwacja na " + when(b) + ". Potwierdź ją albo zaproponuj inny termin.", "requested");
        }
    }

    void accepted(Booking b) {
        toCustomer(b, "Usługodawca potwierdził rezerwację na " + when(b) + ".", "accepted");
    }

    void proposed(Booking b) {
        toCustomer(b, "Usługodawca proponuje inny termin: " + when(b) + ". Przyjmij go w rezerwacji.", "proposed-" + b.getStart().toEpochMilli());
    }

    void proposalAccepted(Booking b) {
        toProvider(b, "Klient przyjął nowy termin: " + when(b) + ".", "proposal-accepted");
    }

    void completed(Booking b) {
        toCustomer(b, "Usługa została wykonana. Możesz wystawić opinię.", "completed");
    }

    void cancelled(Booking b, CancelledBy by) {
        switch (by) {
            case PROVIDER -> toCustomer(b, "Usługodawca anulował rezerwację na " + when(b) + ". Zaliczka wraca do Ciebie.", "cancelled");
            case SYSTEM -> toCustomer(b, b.getPaymentId() == null && b.getDeposit() > 0
                    ? "Rezerwacja wygasła, bo nie wpłacono zaliczki w terminie."
                    : "Rezerwacja na " + when(b) + " wygasła, bo termin minął bez potwierdzenia." + (b.getPaymentId() != null ? " Zaliczka wraca do Ciebie." : ""), "cancelled");
            // Rezerwacja czekająca na zaliczkę nie dotarła jeszcze do usługodawcy, więc nie ma o czym go informować.
            case CUSTOMER -> {
                if (b.getDeposit() == 0 || b.getPaymentId() != null) {
                    toProvider(b, "Klient anulował rezerwację na " + when(b) + ".", "cancelled");
                }
            }
        }
    }

    private void toCustomer(Booking b, String body, String event) {
        notifications.send(b.getCustomerId(), "bookings", b.getOfferTitle(), body, CUSTOMER_LINK, key(b, event));
    }

    private void toProvider(Booking b, String body, String event) {
        notifications.send(b.getProviderId(), "bookings", b.getOfferTitle(), body, PROVIDER_LINK, key(b, event + "-provider"));
    }

    private String when(Booking b) {
        return when.format(b.getStart());
    }

    private static String key(Booking b, String event) {
        return "booking-" + b.getId() + "-" + event;
    }
}

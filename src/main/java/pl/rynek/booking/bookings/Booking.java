package pl.rynek.booking.bookings;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rezerwacja terminu usługi. {@code @Version}: klient anulujący i usługodawca akceptujący w tej samej chwili nie
 * nadpiszą sobie statusu (drugi dostaje 409). Anulowanie czeka w {@code paymentNotified}, aż payment-service je przyjmie.
 */
@Entity
@Table(name = "bookings")
public class Booking {

    /** Kto anulował: wpływa na komunikat dla drugiej strony. */
    public enum CancelledBy { CUSTOMER, PROVIDER, SYSTEM }

    @Id
    private UUID id;

    @Column(name = "offer_id")
    private UUID offerId;

    @Column(name = "offer_title")
    private String offerTitle;

    @Column(name = "channel_id")
    private UUID channelId;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "requested_start")
    private Instant requestedStart;

    private String address;

    private String notes;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    @Column(name = "estimated_price")
    private long estimatedPrice;

    private long deposit;

    @Column(name = "hold_until")
    private Instant holdUntil;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by")
    private CancelledBy cancelledBy;

    @Column(name = "payment_notified")
    private boolean paymentNotified = true;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private int version;

    protected Booking() {
    }

    Booking(Draft d, String idempotencyKey, Duration depositHold, Instant now) {
        this.id = UUID.randomUUID();
        this.offerId = d.offerId();
        this.offerTitle = d.offerTitle();
        this.channelId = d.channelId();
        this.providerId = d.providerId();
        this.customerId = d.customerId();
        this.customerName = d.customerName();
        this.startAt = d.start();
        this.endAt = d.end();
        this.address = d.address();
        this.notes = d.notes();
        this.estimatedPrice = d.estimatedPrice();
        this.deposit = d.deposit();
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
        if (deposit > 0) {
            this.status = BookingStatus.AWAITING_DEPOSIT;
            this.holdUntil = now.plus(depositHold);
        } else {
            this.status = BookingStatus.REQUESTED;
        }
    }

    /** Dane nowej rezerwacji (kopia usługi i ceny z chwili rezerwacji). */
    record Draft(UUID offerId, String offerTitle, UUID channelId, UUID providerId, UUID customerId, String customerName, Instant start,
                 Instant end, String address, String notes, long estimatedPrice, long deposit) {
    }

    /** Zaliczka wpłacona (payment-service). Ponowienie dla opłaconej rezerwacji niczego nie zmienia. */
    void markPaid(UUID paymentId, Instant now) {
        if (status == BookingStatus.CANCELLED) {
            throw conflict("Rezerwacja została anulowana, więc nie można wpłacić zaliczki.");
        }
        if (status == BookingStatus.AWAITING_DEPOSIT) {
            this.paymentId = paymentId;
            this.holdUntil = null;
            move(BookingStatus.REQUESTED, now);
        }
    }

    void accept(Instant now) {
        require(BookingStatus.REQUESTED, "Zaakceptować można rezerwację, która czeka na Twoją decyzję.");
        move(BookingStatus.CONFIRMED, now);
    }

    /** Usługodawca proponuje inny termin; zapamiętujemy termin wybrany przez klienta. */
    void propose(Instant newStart, Duration length, Instant now) {
        if (status != BookingStatus.REQUESTED && status != BookingStatus.PROPOSED) {
            throw conflict("Inny termin zaproponujesz, zanim zaakceptujesz rezerwację.");
        }
        if (!newStart.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Zaproponuj termin w przyszłości.");
        }
        if (requestedStart == null) {
            this.requestedStart = startAt;
        }
        this.startAt = newStart;
        this.endAt = newStart.plus(length);
        move(BookingStatus.PROPOSED, now);
    }

    void acceptProposal(Instant now) {
        require(BookingStatus.PROPOSED, "Usługodawca nie zaproponował innego terminu.");
        move(BookingStatus.CONFIRMED, now);
    }

    void complete(boolean early, Instant now) {
        require(BookingStatus.CONFIRMED, "Wykonać można tylko potwierdzoną rezerwację.");
        if (!early && startAt.isAfter(now)) {
            throw conflict("Usługę oznaczysz jako wykonaną po jej terminie.");
        }
        move(BookingStatus.COMPLETED, now);
    }

    /** Anulowanie przed terminem; potwierdzonej rezerwacji po terminie nie da się już anulować. */
    void cancel(CancelledBy by, String reason, Instant now) {
        if (!BookingStatus.ACTIVE.contains(status)) {
            throw conflict(status == BookingStatus.CANCELLED ? "Rezerwacja jest już anulowana." : "Wykonanej usługi nie można anulować.");
        }
        if (by != CancelledBy.SYSTEM && status == BookingStatus.CONFIRMED && !startAt.isAfter(now)) {
            throw conflict("Termin już minął, więc rezerwacji nie można anulować.");
        }
        this.cancelledBy = by;
        this.cancelReason = reason;
        this.holdUntil = null;
        this.paymentNotified = false;
        move(BookingStatus.CANCELLED, now);
    }

    void paymentNotified() {
        this.paymentNotified = true;
    }

    private void require(BookingStatus expected, String message) {
        if (status != expected) {
            throw conflict(message);
        }
    }

    private void move(BookingStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOfferId() {
        return offerId;
    }

    public String getOfferTitle() {
        return offerTitle;
    }

    public UUID getChannelId() {
        return channelId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public Instant getStart() {
        return startAt;
    }

    public Instant getEnd() {
        return endAt;
    }

    public Instant getRequestedStart() {
        return requestedStart;
    }

    public String getAddress() {
        return address;
    }

    public String getNotes() {
        return notes;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public long getEstimatedPrice() {
        return estimatedPrice;
    }

    public long getDeposit() {
        return deposit;
    }

    public Instant getHoldUntil() {
        return holdUntil;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public CancelledBy getCancelledBy() {
        return cancelledBy;
    }

    public boolean isPaymentNotified() {
        return paymentNotified;
    }
}

package pl.rynek.booking.api.dto;

import java.time.Instant;
import java.util.UUID;

import pl.rynek.booking.bookings.Booking;
import pl.rynek.booking.bookings.BookingStatus;
import pl.rynek.booking.clients.ChannelClient.ChannelRef;

/**
 * Kształt {@code Booking} z rynek-frontend plus {@code end}, {@code requestedStart} (termin klienta, gdy usługodawca
 * zaproponował inny), {@code holdUntil} (do kiedy czeka na zaliczkę), {@code cancelReason} i {@code paymentId}.
 */
public record BookingResponse(UUID id, UUID offerId, String offerTitle, ChannelRef channel, String customerName, Instant start, Instant end,
                              Instant requestedStart, String address, String notes, BookingStatus status, MoneyDto estimatedPrice,
                              MoneyDto deposit, Instant holdUntil, String cancelReason, UUID paymentId, boolean reviewed) {

    public static BookingResponse of(Booking b, ChannelRef channel) {
        return new BookingResponse(b.getId(), b.getOfferId(), b.getOfferTitle(), channel, b.getCustomerName(), b.getStart(), b.getEnd(),
                b.getRequestedStart(), b.getAddress(), b.getNotes(), b.getStatus(), MoneyDto.pln(b.getEstimatedPrice()),
                MoneyDto.pln(b.getDeposit()), b.getHoldUntil(), b.getCancelReason(), b.getPaymentId(), false);
    }
}

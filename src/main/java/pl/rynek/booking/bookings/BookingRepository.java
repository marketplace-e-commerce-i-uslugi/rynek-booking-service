package pl.rynek.booking.bookings;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    /** Aktywne rezerwacje usługi nachodzące na przedział (do wolnych slotów). */
    @Query("select b from Booking b where b.offerId = :offerId and b.startAt < :to and b.endAt > :from"
            + " and b.status in (pl.rynek.booking.bookings.BookingStatus.AWAITING_DEPOSIT, pl.rynek.booking.bookings.BookingStatus.REQUESTED,"
            + " pl.rynek.booking.bookings.BookingStatus.PROPOSED, pl.rynek.booking.bookings.BookingStatus.CONFIRMED)")
    List<Booking> findActiveOverlapping(UUID offerId, Instant from, Instant to);

    @Query(value = "select * from bookings where customer_id = :customerId and (cast(:status as varchar) is null or status = cast(:status as varchar))"
            + " order by start_at desc, id limit :limit offset :offset", nativeQuery = true)
    List<Booking> findOfCustomer(UUID customerId, String status, int offset, int limit);

    /** Rezerwacje usługodawcy od najbliższej; bez czekających na zaliczkę (klient jeszcze nie zapłacił). */
    @Query(value = "select * from bookings where provider_id = :providerId and status <> 'AWAITING_DEPOSIT'"
            + " and (cast(:status as varchar) is null or status = cast(:status as varchar))"
            + " and (cast(:from as timestamptz) is null or start_at >= cast(:from as timestamptz))"
            + " order by start_at, id limit :limit offset :offset", nativeQuery = true)
    List<Booking> findOfProvider(UUID providerId, String status, Instant from, int offset, int limit);

    @Query("select b.id from Booking b where b.status = pl.rynek.booking.bookings.BookingStatus.AWAITING_DEPOSIT and b.holdUntil < :now")
    List<UUID> findHoldExpired(Instant now);

    @Query("select b.id from Booking b where b.paymentNotified = false order by b.updatedAt")
    List<UUID> findPaymentUnnotified();

    List<Booking> findByIdIn(Collection<UUID> ids);

    /** Wykonane usługi kanału z terminem w przedziale {@code [from, to)} (sprzedaż dla analytics-service). */
    @Query("select b from Booking b where b.channelId = :channelId and b.status = pl.rynek.booking.bookings.BookingStatus.COMPLETED"
            + " and b.startAt >= :from and b.startAt < :to order by b.startAt, b.id")
    List<Booking> findCompletedOfChannel(UUID channelId, Instant from, Instant to);
}

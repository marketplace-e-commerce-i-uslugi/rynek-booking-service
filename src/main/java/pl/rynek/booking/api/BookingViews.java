package pl.rynek.booking.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import pl.rynek.booking.api.dto.BookingResponse;
import pl.rynek.booking.api.dto.CursorPage;
import pl.rynek.booking.bookings.Booking;
import pl.rynek.booking.bookings.BookingStatus;
import pl.rynek.booking.clients.ChannelClient;
import pl.rynek.booking.clients.ChannelClient.ChannelRef;

/** Rezerwacje z nagłówkami kanałów (channel-service) i paginacja kursorowa. Kursor to zakodowane przesunięcie. */
@Component
class BookingViews {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final ChannelClient channels;

    BookingViews(ChannelClient channels) {
        this.channels = channels;
    }

    BookingResponse one(Booking booking) {
        return many(List.of(booking)).getFirst();
    }

    List<BookingResponse> many(List<Booking> bookings) {
        Map<UUID, ChannelRef> refs = channels.batch(bookings.stream().map(Booking::getChannelId).toList());
        return bookings.stream().map(b -> BookingResponse.of(b, refs.getOrDefault(b.getChannelId(), ChannelRef.missing(b.getChannelId())))).toList();
    }

    CursorPage<BookingResponse> page(String cursor, Integer limit, Fetch fetch) {
        int offset = offset(cursor);
        int size = limit(limit);
        List<Booking> rows = fetch.fetch(offset, size + 1);
        boolean more = rows.size() > size;
        String next = more ? Base64.getUrlEncoder().withoutPadding().encodeToString(("o:" + (offset + size)).getBytes(StandardCharsets.UTF_8)) : null;
        return new CursorPage<>(many(more ? rows.subList(0, size) : rows), next);
    }

    static BookingStatus status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return BookingStatus.parse(value).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nie ma takiego statusu rezerwacji."));
    }

    private static int offset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int offset = decoded.startsWith("o:") ? Integer.parseInt(decoded.substring(2)) : -1;
            if (offset < 0) {
                throw new IllegalArgumentException();
            }
            return offset;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidłowy kursor. Zacznij od pierwszej strony.");
        }
    }

    private static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit musi wynosić od 1 do " + MAX_LIMIT + ".");
        }
        return limit;
    }

    @FunctionalInterface
    interface Fetch {
        List<Booking> fetch(int offset, int limit);
    }
}

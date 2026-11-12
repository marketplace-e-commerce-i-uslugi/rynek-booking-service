package pl.rynek.booking.clients;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Zgłoszenie anulowania rezerwacji do payment-service (zamiast zdarzenia {@code booking.cancelled}): zwrot wpłaconej
 * zaliczki i zamknięcie płatności w toku.
 */
@Component
public class PaymentClient {

    private final RestClient http;

    public PaymentClient(@Value("${rynek.payment.url}") String paymentUrl) {
        this.http = Clients.http(paymentUrl);
    }

    /** {@code DONE}: przyjęte; {@code REJECTED}: 4xx, ponawianie nic nie da; {@code UNAVAILABLE}: spróbować później. */
    public enum Result { DONE, REJECTED, UNAVAILABLE }

    public Result bookingCancelled(UUID bookingId) {
        try {
            http.post().uri("/internal/bookings/{id}/cancelled", bookingId).retrieve().toBodilessEntity();
            return Result.DONE;
        } catch (HttpClientErrorException e) {
            return Result.REJECTED;
        } catch (RestClientResponseException | ResourceAccessException e) {
            return Result.UNAVAILABLE;
        }
    }
}

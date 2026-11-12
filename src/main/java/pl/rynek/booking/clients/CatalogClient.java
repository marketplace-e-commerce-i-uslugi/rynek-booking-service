package pl.rynek.booking.clients;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Usługa z catalog-service: tytuł, kanał, właściciel i cennik ({@code GET /internal/offers/{id}/price-and-stock}). */
@Component
public class CatalogClient {

    private final RestClient http;

    public CatalogClient(@Value("${rynek.catalog.url}") String catalogUrl) {
        this.http = Clients.http(catalogUrl);
    }

    /** Oferta albo pusta, gdy nie istnieje lub została usunięta. */
    public Optional<OfferInfo> offer(UUID offerId) {
        try {
            return Optional.ofNullable(http.get().uri("/internal/offers/{id}/price-and-stock", offerId).retrieve().body(OfferInfo.class));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest e) {
            return Optional.empty();
        } catch (RestClientResponseException | ResourceAccessException e) {
            throw Clients.unavailable("Katalog ofert");
        }
    }

    public record Money(long amount, String currency) {
    }

    public record Service(String unit, int durationMin, int depositPercent) {
    }

    /** {@code price} to cena „od” usługi; {@code service} jest pusty dla produktu i usługi bez cennika. */
    public record OfferInfo(UUID offerId, String type, Money price, boolean available, String title, UUID channelId, UUID ownerId,
                            Service service) {

        /** Usługę da się zarezerwować: opublikowana i z cennikiem. */
        public boolean bookable() {
            return "SERVICE".equals(type) && available && service != null;
        }
    }
}

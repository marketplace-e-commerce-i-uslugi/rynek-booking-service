package pl.rynek.booking.clients;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Imię klienta do rezerwacji (identity-service). Bez identity rezerwacja i tak powstaje, z zastępczym imieniem. */
@Component
public class IdentityClient {

    private static final Logger log = LoggerFactory.getLogger(IdentityClient.class);

    private final RestClient http;

    public IdentityClient(@Value("${rynek.identity.url}") String identityUrl) {
        this.http = Clients.http(identityUrl);
    }

    public Optional<String> displayName(UUID userId) {
        try {
            User user = http.get().uri("/internal/users/{id}", userId).retrieve().body(User.class);
            return Optional.ofNullable(user).map(User::displayName).filter(n -> !n.isBlank());
        } catch (RestClientResponseException | ResourceAccessException e) {
            log.warn("Brak danych użytkownika {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    record User(UUID id, String displayName) {
    }
}

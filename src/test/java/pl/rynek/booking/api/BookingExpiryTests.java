package pl.rynek.booking.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pl.rynek.booking.TestcontainersConfig;
import pl.rynek.booking.bookings.BookingService;
import pl.rynek.booking.clients.CatalogClient;
import pl.rynek.booking.clients.CatalogClient.Money;
import pl.rynek.booking.clients.CatalogClient.OfferInfo;
import pl.rynek.booking.clients.CatalogClient.Service;
import pl.rynek.booking.clients.ChannelClient;
import pl.rynek.booking.clients.IdentityClient;
import pl.rynek.booking.clients.PaymentClient;

/** Przetrzymanie terminu na zaliczkę: bez wpłaty rezerwacja wygasa (tu przetrzymanie trwa 0 s), a slot jest znów wolny. */
@SpringBootTest(properties = {"rynek.bookings.deposit-hold=0s", "rynek.bookings.expiry-check=1h", "rynek.bookings.payment-retry=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class BookingExpiryTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    BookingService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    CatalogClient catalog;

    @MockitoBean
    ChannelClient channels;

    @MockitoBean
    IdentityClient identity;

    @MockitoBean
    PaymentClient payment;

    @Test
    void unpaidBookingExpiresAndFreesTheSlot() throws Exception {
        given(jwtDecoder.decode(anyString())).willAnswer(call -> Jwt.withTokenValue(call.getArgument(0)).header("alg", "RS256")
                .subject(call.<String>getArgument(0).substring(5)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build());
        UUID offer = UUID.randomUUID();
        given(catalog.offer(offer)).willReturn(Optional.of(new OfferInfo(offer, "SERVICE", new Money(10000, "PLN"), true, "Sprzątanie",
                UUID.randomUUID(), UUID.randomUUID(), new Service("usługa", 60, 30))));
        given(channels.batch(any())).willReturn(Map.of());
        given(identity.displayName(any())).willReturn(Optional.empty());
        given(payment.bookingCancelled(any())).willReturn(PaymentClient.Result.DONE);

        String slots = mvc.perform(get("/api/v1/services/" + offer + "/slots").param("from", Instant.now().plus(Duration.ofHours(1)).toString())
                .param("to", Instant.now().plus(Duration.ofDays(7)).toString())).andReturn().getResponse().getContentAsString();
        String start = JsonPath.<List<String>>read(slots, "$[?(@.free == true)].start").getFirst();
        String body = "{\"offerId\":\"" + offer + "\",\"start\":\"" + start + "\",\"address\":\"ul. Krótka 1\"}";
        String id = JsonPath.read(mvc.perform(post("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer user-" + UUID.randomUUID())
                        .header("Idempotency-Key", "k1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("Czeka na zaliczkę"))
                .andExpect(jsonPath("$.customerName").value("Klient"))
                .andReturn().getResponse().getContentAsString(), "$.id");

        Thread.sleep(20);
        service.expireHolds();

        mvc.perform(get("/internal/bookings/" + id)).andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(payment).bookingCancelled(UUID.fromString(id));
        mvc.perform(post("/internal/bookings/" + id + "/mark-paid")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer user-" + UUID.randomUUID())
                .header("Idempotency-Key", "k2").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    }
}

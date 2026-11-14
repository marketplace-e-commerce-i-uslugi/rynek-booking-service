package pl.rynek.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import pl.rynek.booking.TestcontainersConfig;
import pl.rynek.booking.bookings.BookingRepository;
import pl.rynek.booking.bookings.BookingService;
import pl.rynek.booking.clients.CatalogClient;
import pl.rynek.booking.clients.CatalogClient.Money;
import pl.rynek.booking.clients.CatalogClient.OfferInfo;
import pl.rynek.booking.clients.CatalogClient.Service;
import pl.rynek.booking.clients.ChannelClient;
import pl.rynek.booking.clients.IdentityClient;
import pl.rynek.booking.clients.NotificationClient;
import pl.rynek.booking.clients.PaymentClient;

/**
 * Rezerwacje na prawdziwym PostgreSQL. Atrapa katalogu trzyma usługi w mapie; usługodawca pracuje codziennie 8–20,
 * slot 60 minut, więc w najbliższych dniach zawsze są wolne terminy.
 */
@SpringBootTest(properties = {"rynek.bookings.expiry-check=1h", "rynek.bookings.payment-retry=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class BookingFlowTests {

    static final String EVERY_DAY = "{\"weekly\":[" + String.join(",", List.of(1, 2, 3, 4, 5, 6, 7).stream()
            .map(d -> "{\"day\":" + d + ",\"from\":\"08:00\",\"to\":\"20:00\"}").toList()) + "],\"exceptions\":[],\"slotMinutes\":60}";

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Autowired
    BookingService service;

    @Autowired
    BookingRepository repository;

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

    @MockitoBean
    NotificationClient notifications;

    final Map<UUID, OfferInfo> offers = new ConcurrentHashMap<>();

    @BeforeEach
    void stubs() {
        given(jwtDecoder.decode(anyString())).willAnswer(call -> {
            String token = call.getArgument(0);
            if (!token.startsWith("user-")) {
                throw new BadJwtException("Nieważny token");
            }
            return Jwt.withTokenValue(token).header("alg", "RS256").subject(token.substring(5))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(900)).build();
        });
        given(catalog.offer(any())).willAnswer(call -> Optional.ofNullable(offers.get(call.<UUID>getArgument(0))));
        given(channels.batch(any())).willReturn(Map.of());
        given(identity.displayName(any())).willReturn(Optional.of("Anna Nowak"));
        given(payment.bookingCancelled(any())).willReturn(PaymentClient.Result.DONE);
    }

    @Test
    void availabilityBelongsToProviderAndDefaultsToWeekdays() throws Exception {
        UUID provider = UUID.randomUUID();
        UUID offer = service(provider, 15000, "godz.", 90, 0);
        mvc.perform(get("/api/v1/services/" + offer + "/availability").header(HttpHeaders.AUTHORIZATION, bearer(provider)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekly", hasSize(5)))
                .andExpect(jsonPath("$.weekly[0].from").value("09:00"))
                .andExpect(jsonPath("$.slotMinutes").value(90));
        mvc.perform(get("/api/v1/services/" + offer + "/availability").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        setAvailability(provider, offer, "{\"weekly\":[{\"day\":0,\"from\":\"10:00\",\"to\":\"14:00\"}],\"exceptions\":[{\"date\":\"2026-12-24\",\"closed\":true}],\"slotMinutes\":30}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekly[0].day").value(7))
                .andExpect(jsonPath("$.exceptions[0].date").value("2026-12-24"));
        setAvailability(provider, offer, "{\"weekly\":[],\"exceptions\":[],\"slotMinutes\":20}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Długość slotu to od 15 do 480 minut, co 15 minut."));
        setAvailability(UUID.randomUUID(), offer, EVERY_DAY).andExpect(status().isNotFound());
    }

    @Test
    void internalNextSlotsAnswerManyServicesAtOnce() throws Exception {
        UUID provider = UUID.randomUUID();
        UUID open = service(provider, 15000, "godz.", 120, 0);
        UUID noRules = service(provider, 15000, "godz.", 120, 0);
        setAvailability(provider, open, EVERY_DAY).andExpect(status().isOk());
        UUID unknown = UUID.randomUUID();
        String ids = open + "," + noRules + "," + unknown;
        String body = mvc.perform(get("/internal/services/next-slots").param("ids", ids)).andExpect(status().isOk())
                .andExpect(jsonPath("$['" + open + "']").exists())
                .andExpect(jsonPath("$['" + unknown + "']").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(body, "$['" + open + "']"))).isAfter(Instant.now().minusSeconds(1));
        mvc.perform(get("/internal/services/next-slots")).andExpect(status().isBadRequest());
    }

    @Test
    void bookingWithoutDepositFromRequestToCancellation() throws Exception {
        UUID provider = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID offer = service(provider, 15000, "godz.", 120, 0);
        setAvailability(provider, offer, EVERY_DAY).andExpect(status().isOk());
        String start = freeSlot(offer, 0);

        String body = book(customer, "k1", offer, start).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("Oczekuje"))
                .andExpect(jsonPath("$.customerName").value("Anna Nowak"))
                .andExpect(jsonPath("$.estimatedPrice.amount").value(30000))
                .andExpect(jsonPath("$.deposit.amount").value(0))
                .andExpect(jsonPath("$.holdUntil").doesNotExist())
                .andExpect(jsonPath("$.channel.name").value("Usługodawca"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        // Ten sam klucz: ta sama rezerwacja. Inny klient na ten sam slot: zajęty.
        book(customer, "k1", offer, start).andExpect(jsonPath("$.id").value(id));
        book(UUID.randomUUID(), "k2", offer, start).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ten termin jest już zajęty albo niedostępny. Wybierz inny."));
        book(provider, "k3", offer, freeSlot(offer, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Nie możesz zarezerwować własnej usługi."));
        book(customer, "k4", offer, "2020-01-01T09:00:00Z").andExpect(status().isConflict());
        mvc.perform(get("/api/v1/services/" + offer + "/slots").param("from", start).param("to", start))
                .andExpect(jsonPath("$[0].free").value(false));
        // next-slot liczy od "teraz", a freeSlot od "teraz + 2 h": w godzinach pracy pierwszy wolny slot to nie ten sam.
        String next = JsonPath.read(mvc.perform(get("/api/v1/services/" + offer + "/next-slot")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.start");
        assertThat(Instant.parse(next)).isAfter(Instant.now().minusSeconds(1)).isBeforeOrEqualTo(Instant.parse(freeSlot(offer, 0)));

        mvc.perform(get("/api/v1/seller/bookings").header(HttpHeaders.AUTHORIZATION, bearer(provider)))
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].status").value("Oczekuje"));
        mvc.perform(get("/api/v1/bookings/" + id).header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))).andExpect(status().isNotFound());
        seller(provider, id, "accept").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Potwierdzona"));
        seller(provider, id, "accept").andExpect(status().isConflict());
        // Przed terminem usługi nie da się oznaczyć jako wykonanej.
        seller(provider, id, "complete").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Usługę oznaczysz jako wykonaną po jej terminie."));
        mvc.perform(get("/api/v1/bookings/" + id + "/calendar.ics").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/calendar")))
                .andExpect(content().string(containsString("BEGIN:VEVENT")))
                .andExpect(content().string(containsString("SUMMARY:Malowanie ścian")))
                .andExpect(content().string(containsString("LOCATION:ul. Długa 5\\, Warszawa")));

        cancel(customer, id, "{\"reason\":\"Wyjeżdżam\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Anulowana"))
                .andExpect(jsonPath("$.cancelReason").value("Wyjeżdżam"));
        verify(payment).bookingCancelled(UUID.fromString(id));
        cancel(customer, id, null).andExpect(status().isConflict());
        // Termin znów jest wolny.
        book(UUID.randomUUID(), "k5", offer, start).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/bookings/mine").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].status").value("Anulowana"));
    }

    @Test
    void depositHoldsSlotUntilPaidAndProviderCanProposeAnotherTime() throws Exception {
        UUID provider = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID offer = service(provider, 2500, "m²", 240, 20);
        setAvailability(provider, offer, EVERY_DAY).andExpect(status().isOk());
        String start = freeSlot(offer, 0);
        String id = JsonPath.read(book(customer, "k1", offer, start).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("Czeka na zaliczkę"))
                .andExpect(jsonPath("$.estimatedPrice.amount").value(2500))
                .andExpect(jsonPath("$.deposit.amount").value(500))
                .andExpect(jsonPath("$.holdUntil").exists())
                .andReturn().getResponse().getContentAsString(), "$.id");
        // Usługodawca nie widzi niezapłaconej rezerwacji i nie może jej zaakceptować.
        mvc.perform(get("/api/v1/seller/bookings").header(HttpHeaders.AUTHORIZATION, bearer(provider))).andExpect(jsonPath("$.items", hasSize(0)));
        seller(provider, id, "accept").andExpect(status().isConflict());

        mvc.perform(get("/internal/bookings/" + id))
                .andExpect(jsonPath("$.status").value("AWAITING_DEPOSIT"))
                .andExpect(jsonPath("$.providerId").value(provider.toString()))
                .andExpect(jsonPath("$.deposit").value(500));
        UUID paymentId = UUID.randomUUID();
        mvc.perform(post("/internal/bookings/" + id + "/mark-paid").contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"" + paymentId + "\"}"))
                .andExpect(jsonPath("$.status").value("REQUESTED"));
        mvc.perform(post("/internal/bookings/" + id + "/mark-paid")).andExpect(jsonPath("$.status").value("REQUESTED"));
        mvc.perform(get("/internal/bookings").param("ids", id + "," + UUID.randomUUID())).andExpect(jsonPath("$", hasSize(1)));

        String other = freeSlot(offer, 2);
        mvc.perform(post("/api/v1/seller/bookings/" + id + "/propose-time").header(HttpHeaders.AUTHORIZATION, bearer(provider))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"start\":\"" + other + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Inny termin"))
                .andExpect(jsonPath("$.start").value(other))
                .andExpect(jsonPath("$.requestedStart").value(start));
        // Nowy termin zajęty: tego samego slotu nie zarezerwuje ktoś inny.
        book(UUID.randomUUID(), "k2", offer, other).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/bookings/" + id + "/accept-proposal").header(HttpHeaders.AUTHORIZATION, bearer(provider))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bookings/" + id + "/accept-proposal").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Potwierdzona"));

        // Usługodawca anuluje potwierdzoną: zaliczka wraca (zgłoszenie do payment-service).
        cancel(provider, id, null).andExpect(jsonPath("$.status").value("Anulowana"))
                .andExpect(jsonPath("$.cancelReason").value("Usługodawca anulował rezerwację."));
        mvc.perform(post("/internal/bookings/" + id + "/mark-paid")).andExpect(status().isConflict());
    }

    /** Powiadomienia w dzwonku: usługodawca widzi rezerwację dopiero po zaliczce, klient dostaje decyzje usługodawcy. */
    @Test
    void salesForAnalyticsAreCompletedServicesWithTheirTermInTheRange() throws Exception {
        UUID provider = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID offer = service(provider, 15000, "godz.", 120, 0);
        setAvailability(provider, offer, EVERY_DAY).andExpect(status().isOk());
        String done = JsonPath.read(book(customer, "a1", offer, freeSlot(offer, 0)).andReturn().getResponse().getContentAsString(), "$.id");
        String pending = JsonPath.read(book(customer, "a2", offer, freeSlot(offer, 1)).andReturn().getResponse().getContentAsString(), "$.id");
        // „Wykonana” wymaga terminu w przeszłości, więc przesuwamy rezerwację w bazie.
        jdbc.update("update bookings set status = 'COMPLETED', start_at = now() - interval '2 hours', end_at = now() - interval '1 hour' where id = ?::uuid", done);

        UUID channel = jdbc.queryForObject("select channel_id from bookings where id = ?::uuid", UUID.class, done);
        String from = Instant.now().minusSeconds(86400).toString();
        String to = Instant.now().plusSeconds(3600).toString();
        mvc.perform(get("/internal/analytics/sales").param("channelId", channel.toString()).param("from", from).param("to", to))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(done))
                .andExpect(jsonPath("$[0].amount").value(30000)).andExpect(jsonPath("$[0].offerId").value(offer.toString()));
        mvc.perform(get("/internal/analytics/sales").param("channelId", UUID.randomUUID().toString()).param("from", from).param("to", to))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/internal/analytics/sales").param("channelId", channel.toString()).param("from", to).param("to", from))
                .andExpect(status().isBadRequest());
        assertThat(pending).isNotEqualTo(done);
    }

    @Test
    void bookingChangesAreReportedToNotificationService() throws Exception {
        UUID provider = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID offer = service(provider, 2500, "m²", 240, 20);
        setAvailability(provider, offer, EVERY_DAY).andExpect(status().isOk());
        String id = JsonPath.read(book(customer, "n1", offer, freeSlot(offer, 0)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        // Czeka na zaliczkę: usługodawca jeszcze o niej nie wie.
        verify(notifications, never()).send(any(), anyString(), anyString(), anyString(), any(), any());

        mvc.perform(post("/internal/bookings/" + id + "/mark-paid").contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isOk());
        verify(notifications, atLeastOnce()).send(eq(provider), eq("bookings"), eq("Malowanie ścian"), startsWith("Nowa rezerwacja na "),
                eq("/sprzedawca/rezerwacje"), eq("booking-" + id + "-requested-provider"));

        String other = freeSlot(offer, 2);
        mvc.perform(post("/api/v1/seller/bookings/" + id + "/propose-time").header(HttpHeaders.AUTHORIZATION, bearer(provider))
                .contentType(MediaType.APPLICATION_JSON).content("{\"start\":\"" + other + "\"}")).andExpect(status().isOk());
        verify(notifications).send(eq(customer), eq("bookings"), eq("Malowanie ścian"), startsWith("Usługodawca proponuje inny termin: "),
                eq("/profil/rezerwacje"), startsWith("booking-" + id + "-proposed-"));
        mvc.perform(post("/api/v1/bookings/" + id + "/accept-proposal").header(HttpHeaders.AUTHORIZATION, bearer(customer))).andExpect(status().isOk());
        verify(notifications).send(eq(provider), eq("bookings"), eq("Malowanie ścian"), startsWith("Klient przyjął nowy termin"),
                eq("/sprzedawca/rezerwacje"), eq("booking-" + id + "-proposal-accepted-provider"));

        cancel(provider, id, null).andExpect(status().isOk());
        verify(notifications).send(eq(customer), eq("bookings"), eq("Malowanie ścian"), startsWith("Usługodawca anulował rezerwację"),
                eq("/profil/rezerwacje"), eq("booking-" + id + "-cancelled"));

        // Bez zaliczki rezerwacja trafia do usługodawcy od razu; potwierdzenie idzie do klienta, anulowanie klienta do usługodawcy.
        UUID free = service(provider, 15000, "godz.", 120, 0);
        setAvailability(provider, free, EVERY_DAY).andExpect(status().isOk());
        String second = JsonPath.read(book(customer, "n2", free, freeSlot(free, 0)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        verify(notifications).send(eq(provider), eq("bookings"), anyString(), startsWith("Nowa rezerwacja na "),
                eq("/sprzedawca/rezerwacje"), eq("booking-" + second + "-requested-provider"));
        seller(provider, second, "accept").andExpect(status().isOk());
        verify(notifications).send(eq(customer), eq("bookings"), anyString(), startsWith("Usługodawca potwierdził rezerwację"),
                eq("/profil/rezerwacje"), eq("booking-" + second + "-accepted"));
        cancel(customer, second, null).andExpect(status().isOk());
        verify(notifications).send(eq(provider), eq("bookings"), anyString(), startsWith("Klient anulował rezerwację"),
                eq("/sprzedawca/rezerwacje"), eq("booking-" + second + "-cancelled-provider"));
    }

    @Test
    void declineAndPaymentServiceDownIsRetried() throws Exception {
        UUID provider = UUID.randomUUID();
        UUID offer = service(provider, 9900, "usługa", 60, 0);
        setAvailability(provider, offer, EVERY_DAY).andExpect(status().isOk());
        String id = JsonPath.read(book(UUID.randomUUID(), "k1", offer, freeSlot(offer, 0)).andReturn().getResponse().getContentAsString(), "$.id");
        given(payment.bookingCancelled(any())).willReturn(PaymentClient.Result.UNAVAILABLE);
        seller(provider, id, "decline").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Anulowana"))
                .andExpect(jsonPath("$.cancelReason").value("Usługodawca odrzucił rezerwację."));
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().isPaymentNotified()).isFalse();
        seller(provider, id, "decline").andExpect(status().isConflict());

        given(payment.bookingCancelled(any())).willReturn(PaymentClient.Result.DONE);
        service.notifyPaymentPending();
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().isPaymentNotified()).isTrue();
        verify(payment, times(2)).bookingCancelled(UUID.fromString(id));
    }

    @Test
    void validationAndUnknownService() throws Exception {
        UUID customer = UUID.randomUUID();
        mvc.perform(get("/api/v1/services/" + UUID.randomUUID() + "/slots")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Ta usługa nie przyjmuje teraz rezerwacji."));
        UUID offer = service(UUID.randomUUID(), 9900, "usługa", 60, 0);
        mvc.perform(get("/api/v1/services/" + offer + "/slots").param("from", "jutro")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/services/" + offer + "/slots").param("from", Instant.now().toString())
                .param("to", Instant.now().plus(Duration.ofDays(90)).toString())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, bearer(customer)).header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":\"" + offer + "\",\"start\":\"" + freeSlot(offer, 0) + "\",\"address\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Podaj adres wykonania usługi."));
        mvc.perform(post("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, bearer(customer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"" + offer + "\",\"start\":\"" + freeSlot(offer, 0) + "\",\"address\":\"ul. Długa 5\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Brak nagłówka Idempotency-Key (do 100 znaków)."));
        mvc.perform(get("/api/v1/bookings/mine")).andExpect(status().isUnauthorized());
    }

    private UUID service(UUID provider, long price, String unit, int durationMin, int depositPercent) {
        UUID id = UUID.randomUUID();
        offers.put(id, new OfferInfo(id, "SERVICE", new Money(price, "PLN"), true, "Malowanie ścian", UUID.randomUUID(), provider,
                new Service(unit, durationMin, depositPercent)));
        return id;
    }

    /** {@code n}-ty wolny slot w najbliższych 3 dniach. */
    private String freeSlot(UUID offer, int n) throws Exception {
        String body = mvc.perform(get("/api/v1/services/" + offer + "/slots").param("from", Instant.now().plus(Duration.ofHours(2)).toString())
                .param("to", Instant.now().plus(Duration.ofDays(3)).toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> free = JsonPath.read(body, "$[?(@.free == true)].start");
        return free.get(n);
    }

    private ResultActions setAvailability(UUID provider, UUID offer, String body) throws Exception {
        return mvc.perform(put("/api/v1/services/" + offer + "/availability").header(HttpHeaders.AUTHORIZATION, bearer(provider))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions book(UUID customer, String key, UUID offer, String start) throws Exception {
        return mvc.perform(post("/api/v1/bookings").header(HttpHeaders.AUTHORIZATION, bearer(customer)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"offerId\":\"" + offer + "\",\"start\":\"" + start + "\",\"address\":\"ul. Długa 5, Warszawa\",\"notes\":\"3 piętro\"}"));
    }

    private ResultActions seller(UUID provider, String id, String action) throws Exception {
        return mvc.perform(post("/api/v1/seller/bookings/" + id + "/" + action).header(HttpHeaders.AUTHORIZATION, bearer(provider)));
    }

    private ResultActions cancel(UUID user, String id, String body) throws Exception {
        var request = post("/api/v1/bookings/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, bearer(user));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static String bearer(UUID userId) {
        return "Bearer user-" + userId;
    }
}

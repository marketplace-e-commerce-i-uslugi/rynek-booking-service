package pl.rynek.booking.availability;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

/** Zapisane reguły dostępności jednej usługi. */
@Entity
@Table(name = "availability")
public class Availability {

    @Id
    @Column(name = "offer_id")
    private UUID offerId;

    @Column(name = "provider_id")
    private UUID providerId;

    @Column(name = "slot_minutes")
    private int slotMinutes;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "availability_hours", joinColumns = @JoinColumn(name = "offer_id"))
    @OrderColumn(name = "position")
    private List<Hours> hours = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "availability_exceptions", joinColumns = @JoinColumn(name = "offer_id"))
    @OrderColumn(name = "position")
    private List<Closed> exceptions = new ArrayList<>();

    protected Availability() {
    }

    Availability(UUID offerId, UUID providerId) {
        this.offerId = offerId;
        this.providerId = providerId;
    }

    void replace(Rules rules, Instant now) {
        this.slotMinutes = rules.slotMinutes();
        this.hours.clear();
        rules.weekly().forEach(h -> hours.add(new Hours(h.day(), h.from(), h.to())));
        this.exceptions.clear();
        rules.exceptions().forEach(e -> exceptions.add(new Closed(e.date(), e.closed())));
        this.updatedAt = now;
    }

    Rules rules() {
        return new Rules(hours.stream().map(h -> new Rules.Hours(h.day(), h.fromTime(), h.toTime())).toList(),
                exceptions.stream().map(e -> new Rules.Closed(e.day(), e.closed())).toList(), slotMinutes);
    }

    @Embeddable
    record Hours(int day, @Column(name = "from_time") LocalTime fromTime, @Column(name = "to_time") LocalTime toTime) {
    }

    @Embeddable
    record Closed(LocalDate day, boolean closed) {
    }
}

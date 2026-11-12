package pl.rynek.booking.availability;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AvailabilityRepository extends JpaRepository<Availability, UUID> {
}

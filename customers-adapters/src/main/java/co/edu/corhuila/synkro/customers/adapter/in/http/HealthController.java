package co.edu.corhuila.synkro.customers.adapter.in.http;

import co.edu.corhuila.synkro.customers.application.port.out.CustomerRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.Map;

@RestController
public class HealthController {
    private final CustomerRepository repository;
    private final Clock clock;
    public HealthController(CustomerRepository repository,Clock clock) { this.repository=repository;this.clock=clock; }
    @GetMapping("/health") public ResponseEntity<?> health() {
        boolean ok=repository.healthy();return ResponseEntity.status(ok?200:503).body(Map.of("status",ok?"ok":"degraded","service","synkro-customers-api","timestamp",clock.instant(),"dependencies",Map.of("database",ok?"ok":"down")));
    }
}

package com.remoteroles.pipeline.api;

import com.remoteroles.pipeline.ingest.IngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Operator endpoints.
 *
 * <p>Deliberately unauthenticated and bound to the pipeline service, which is not
 * meant to be internet-facing -- the website talks to Postgres, not to this. If
 * this ever gets a public address it needs auth in front of
 * {@code /admin/ingest/run} first, since that endpoint will cheerfully hammer
 * sixty third-party APIs on demand.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final IngestionService ingestion;

    public AdminController(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /** Triggers a full pass synchronously. Handy in development; slow by nature. */
    @PostMapping("/ingest/run")
    public ResponseEntity<IngestionService.RunSummary> run() {
        return ResponseEntity.ok(ingestion.runOnce());
    }
}

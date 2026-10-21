package com.remoteroles.pipeline.api;

import com.remoteroles.pipeline.ingest.IngestionService;
import com.remoteroles.pipeline.normalize.ScopeRechecker;
import com.remoteroles.pipeline.repo.JobRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import com.remoteroles.pipeline.newsletter.ConfirmationSender;
import com.remoteroles.pipeline.newsletter.DigestSender;
import com.remoteroles.pipeline.newsletter.EmailVerificationSender;
import com.remoteroles.pipeline.newsletter.InviteSender;
import com.remoteroles.pipeline.newsletter.MailBudget;
import com.remoteroles.pipeline.newsletter.PostingDecisionSender;
import com.remoteroles.pipeline.newsletter.ReviewAlerter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.RequestParam;
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

    private final DigestSender digest;
    private final InviteSender invites;
    private final ReviewAlerter reviews;
    private final PostingDecisionSender decisions;
    private final EmailVerificationSender verifications;
    // Conditional on its own switch, so it may genuinely be absent. An
    // ObjectProvider is the difference between a preview that reports one
    // template missing and a service that refuses to start.
    private final ObjectProvider<ConfirmationSender> confirmations;
    private final MailBudget budget;
    private final IngestionService ingestion;
    private final JobRepository jobs;
    private final ScopeRechecker rechecker;

    public AdminController(DigestSender digest, InviteSender invites, ReviewAlerter reviews,
                           PostingDecisionSender decisions,
                           EmailVerificationSender verifications,
                           ObjectProvider<ConfirmationSender> confirmations,
                           MailBudget budget,
                           IngestionService ingestion, JobRepository jobs,
                           ScopeRechecker rechecker) {
        this.budget = budget;
        this.ingestion = ingestion;
        this.jobs = jobs;
        this.rechecker = rechecker;
        this.digest = digest;
        this.invites = invites;
        this.reviews = reviews;
        this.decisions = decisions;
        this.verifications = verifications;
        this.confirmations = confirmations;
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

    /**
     * Re-reads pay out of payloads already stored, without fetching anything.
     *
     * <p>Separate from {@code /ingest/run}, which does the same work at the end of a
     * pass that first calls sixty third-party APIs. When the reason to backfill is a
     * parser that only just learned to read a field, the fetching is pure cost.
     */
    @PostMapping("/backfill/salaries")
    public Map<String, Integer> backfillSalaries() {
        return Map.of("updated", jobs.refreshAshbySalaries());
    }

    /**
     * Replays the scope rules over listings already badged WORLDWIDE.
     *
     * <p>For use the moment a classifier rule changes. Without it a corrected rule
     * only reaches the board as each source's turn comes round, which for the
     * budgeted feeds is weeks.
     */
    @PostMapping("/recheck/worldwide")
    public ScopeRechecker.Result recheckWorldwide() {
        return rechecker.recheckWorldwide();
    }

    /** Retires stored listings that were never roles. See JobRepository#retireNonRoles. */
    /**
     * Sends the digest now, ignoring how long it has been since the last one.
     *
     * <p>Everything else it checks still applies: the kill switch, the minimum
     * number of new roles, and whether there is anyone to send to. This bypasses
     * the clock, not the guards.
     */
    @PostMapping("/newsletter/send")
    public Map<String, Integer> sendDigestNow() {
        return Map.of("recipients", digest.sendIssueNow());
    }

    /**
     * Sends any invitation that is due, immediately.
     *
     * <p>Called by the admin right after addresses are recorded, so the person
     * importing sees the result rather than waiting for the next hourly run.
     * Every rule still applies: the two-send ceiling, the status check, and
     * whether a send was actually requested.
     */
    @PostMapping("/newsletter/invite")
    public Map<String, Integer> sendInvitesNow() {
        return Map.of("sent", invites.sendDueNow());
    }

    /**
     * Sends one copy of every template to a single address, for looking at.
     *
     * <p>There is no other way to see these. Each one is triggered by a real
     * event — somebody subscribing, a week elapsing, an employer submitting — so
     * reviewing a layout change otherwise means waiting for a stranger to act,
     * or manufacturing the event in the database and undoing it afterwards.
     *
     * <p>Sends to whatever address is asked for, which is fine while this port is
     * unreachable from outside the compose network and is the first thing to
     * reconsider if that ever changes: an open endpoint that mails arbitrary
     * recipients is an open relay with extra steps.
     */
    /**
     * Mails the templates to one address, for looking at.
     *
     * <p>A preview is counted against the day but never refused by it. It is an
     * operator action, and a budget that blocked the person trying to find out
     * why mail looks wrong would be in the way at the worst moment; but the
     * relay counts these like any other message, so leaving them out would make
     * the day's total quietly wrong in the direction that matters.
     *
     * <p>{@code only} names a single template. Without it all of them go, which is
     * right the first time and wrong every time after: one template changes, four
     * arrive, and the three that did not change are the ones being compared
     * against a memory of yesterday.
     */
    @PostMapping("/newsletter/preview")
    public Map<String, String> previewTemplates(
            @RequestParam("to") String to,
            @RequestParam(value = "only", required = false) String only) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();

        if (wanted(only, "confirmation")) {
            ConfirmationSender confirmation = confirmations.getIfAvailable();
            if (confirmation == null) {
                result.put("confirmation", "skipped: newsletter.confirmation.enabled is false");
            } else {
                confirmation.sendPreview(to);
                budget.record(MailBudget.Tier.TRANSACTIONAL);
            result.put("confirmation", "sent");
            }
        }

        if (wanted(only, "invite")) {
            invites.sendPreview(to);
            budget.record(MailBudget.Tier.TRANSACTIONAL);
            result.put("invite", "sent");
        }

        if (wanted(only, "digest")) {
            digest.sendPreview(to);
            budget.record(MailBudget.Tier.TRANSACTIONAL);
            result.put("digest", "sent");
        }

        if (wanted(only, "reviewAlert")) {
            reviews.sendPreview(to);
            budget.record(MailBudget.Tier.TRANSACTIONAL);
            result.put("reviewAlert", "sent");
        }

        // Two messages, not one, and separately nameable. "decision" sends both;
        // either half can be asked for alone, which is what reviewing a change
        // to one of them actually needs.
        if (wanted(only, "decision") || "decisionApproved".equalsIgnoreCase(only)) {
            decisions.sendApprovedPreview(to);
            budget.record(MailBudget.Tier.TRANSACTIONAL);
            result.put("decisionApproved", "sent");
        }

        if (wanted(only, "decision") || "decisionRejected".equalsIgnoreCase(only)) {
            decisions.sendRejectedPreview(to);
            budget.record(MailBudget.Tier.TRANSACTIONAL);
            result.put("decisionRejected", "sent");
        }

        if (wanted(only, "verification")) {
            verifications.sendPreview(to);
            budget.record(MailBudget.Tier.TRANSACTIONAL);
            result.put("verification", "sent");
        }

        if (result.isEmpty()) {
            result.put("error", "unknown template: " + only);
        }
        return result;
    }

    private static boolean wanted(String only, String name) {
        return only == null || only.isBlank() || only.equalsIgnoreCase(name);
    }

    @PostMapping("/retire/non-roles")
    public Map<String, Integer> retireNonRoles() {
        return Map.of("closed", jobs.retireNonRoles());
    }
}

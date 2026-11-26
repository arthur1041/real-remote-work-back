package com.remoteroles.pipeline.newsletter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Asks the site, every hour, to credit employers whose referral commissions
 * have confirmed (owner, 25 Nov 2026: employers who refer are paid in credit
 * on their own Stripe invoices, automatically).
 *
 * <p>The work is in the site (`web/lib/referral-credits.ts`), because the site
 * holds the Stripe key and this process does not; this is only the clock, the
 * same split as the posting review. Hourly, so a credit lands within the hour
 * of confirming -- the commission was announced with a date, and the referrer
 * may look on that day.
 */
@Service
public class ReferralCreditTrigger {

    private static final Logger log = LoggerFactory.getLogger(ReferralCreditTrigger.class);

    private final String webBaseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ReferralCreditTrigger(
            // The web process on the compose network, never the public host.
            @Value("${posting-review.web-url:http://web:3000}") String webBaseUrl,
            @Value("${posting-review.token:${INTERNAL_TASK_TOKEN:}}") String token) {
        this.webBaseUrl = webBaseUrl;
        this.token = token;
    }

    @Scheduled(cron = "${referrals.credits.cron:0 20 * * * *}")
    public void run() {
        if (token == null || token.length() < 24) return;
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(webBaseUrl + "/api/internal/referral-credits"))
                            // HTTP/1.1 on purpose: see PostingReviewer -- the
                            // Next server drops an h2c upgrade on plain http.
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("x-internal-token", token)
                            .timeout(Duration.ofSeconds(120))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("referral credits: the site answered {}", response.statusCode());
            } else if (!response.body().contains("\"credited\":0,\"waiting\":0")) {
                log.info("referral credits: {}", response.body());
            }
        } catch (Exception e) {
            log.warn("referral credits: call to the web app failed: {}", e.toString());
        }
    }
}

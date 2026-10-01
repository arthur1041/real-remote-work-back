package com.remoteroles.pipeline.newsletter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Newsletter configuration.
 *
 * <p>{@code delivery.enabled} is the master switch and ships false. Turning it on
 * requires SMTP credentials and a verified sending domain; until both exist, every
 * send is a no-op.
 */
@ConfigurationProperties(prefix = "newsletter")
public record NewsletterProperties(
        Delivery delivery,
        String fromAddress,
        String fromName,
        /** Absolute base URL for confirm and unsubscribe links. */
        String siteUrl
) {
    public record Delivery(boolean enabled) {}
}

package com.remoteroles.pipeline.ats;

import com.remoteroles.pipeline.domain.AtsType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an {@link AtsType} to its fetcher.
 *
 * <p>Built by injecting every {@link AtsFetcher} bean, so adding an ATS means
 * adding one {@code @Component} and nothing else.
 */
@Component
public class FetcherRegistry {

    private final Map<AtsType, AtsFetcher> byType = new EnumMap<>(AtsType.class);

    public FetcherRegistry(List<AtsFetcher> fetchers) {
        for (AtsFetcher fetcher : fetchers) {
            AtsFetcher existing = byType.put(fetcher.supports(), fetcher);
            if (existing != null) {
                throw new IllegalStateException(
                        "two fetchers claim " + fetcher.supports() + ": "
                                + existing.getClass().getSimpleName() + " and "
                                + fetcher.getClass().getSimpleName());
            }
        }
    }

    public AtsFetcher require(AtsType type) {
        AtsFetcher fetcher = byType.get(type);
        if (fetcher == null) {
            throw new IllegalArgumentException("no fetcher registered for " + type);
        }
        return fetcher;
    }
}

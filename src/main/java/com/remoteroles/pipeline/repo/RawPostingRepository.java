package com.remoteroles.pipeline.repo;

import com.remoteroles.pipeline.domain.AtsType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RawPostingRepository {

    private final JdbcClient db;

    public RawPostingRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * Stores a posting version, ignoring it if that exact version is already held.
     *
     * <p>{@code do nothing} on the version constraint is what makes the append-only
     * raw layer affordable: an unchanged posting re-seen on every daily run costs
     * one rejected insert, not a new row forever.
     */
    public void saveIfNew(long companyId, AtsType atsType, String externalId,
                          String contentHash, String payloadJson) {
        db.sql("""
                        insert into raw_postings (company_id, ats_type, external_id, content_hash, payload)
                        values (:companyId, :atsType, :externalId, :contentHash, cast(:payload as jsonb))
                        on conflict (company_id, external_id, content_hash) do nothing
                        """)
                .param("companyId", companyId)
                .param("atsType", atsType.name())
                .param("externalId", externalId)
                .param("contentHash", contentHash)
                .param("payload", payloadJson)
                .update();
    }
}

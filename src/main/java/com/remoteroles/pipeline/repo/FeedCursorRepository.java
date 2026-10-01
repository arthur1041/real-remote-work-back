package com.remoteroles.pipeline.repo;

import com.remoteroles.pipeline.domain.AtsType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FeedCursorRepository {

    private final JdbcClient db;

    public FeedCursorRepository(JdbcClient db) {
        this.db = db;
    }

    /** Where the next crawl of this feed should start. */
    public int nextOffset(AtsType source) {
        return db.sql("select next_offset from feed_cursors where source = :source")
                .param("source", source.name())
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /**
     * Records where a pass stopped.
     *
     * <p>{@code wrapped} means the crawl ran off the end of the feed and the next
     * run restarts from the beginning, which also bumps the lap counter — the only
     * way to tell "we have covered the back catalogue once" from "we are still
     * working through it".
     */
    public void advance(AtsType source, int nextOffset, boolean wrapped, String status) {
        db.sql("""
                        insert into feed_cursors (source, next_offset, laps, last_run_at, last_status)
                        values (:source, :nextOffset, :lap, now(), :status)
                        on conflict (source) do update set
                            next_offset = excluded.next_offset,
                            laps = feed_cursors.laps + :lap,
                            last_run_at = now(),
                            last_status = excluded.last_status
                        """)
                .param("source", source.name())
                .param("nextOffset", wrapped ? 0 : nextOffset)
                .param("lap", wrapped ? 1 : 0)
                .param("status", status)
                .update();
    }
}

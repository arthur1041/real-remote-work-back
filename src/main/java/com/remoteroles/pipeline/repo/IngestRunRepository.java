package com.remoteroles.pipeline.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IngestRunRepository {

    private final JdbcClient db;

    public IngestRunRepository(JdbcClient db) {
        this.db = db;
    }

    public long start() {
        return db.sql("insert into ingest_runs (started_at) values (now()) returning id")
                .query(Long.class)
                .single();
    }

    public void finish(long runId, int total, int ok, int failed,
                       int created, int updated, int closed, String notes) {
        db.sql("""
                        update ingest_runs
                           set finished_at = now(),
                               companies_total = :total,
                               companies_ok = :ok,
                               companies_failed = :failed,
                               jobs_created = :created,
                               jobs_updated = :updated,
                               jobs_closed = :closed,
                               notes = :notes
                         where id = :id
                        """)
                .param("id", runId)
                .param("total", total)
                .param("ok", ok)
                .param("failed", failed)
                .param("created", created)
                .param("updated", updated)
                .param("closed", closed)
                .param("notes", notes)
                .update();
    }
}

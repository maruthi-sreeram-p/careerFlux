package com.careerflux.support;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes exactly what one ingestion test created.
 *
 * <p>The integration suite isolates itself in two different ways. Most tests run
 * inside a transaction the framework rolls back; a handful commit and rely on
 * giving every fixture a unique identity. Ingestion tests cannot use either once
 * M1 and M2 are fixed: rollback will stop covering independently committed inner
 * transactions, and unique identity is not enough because the fixture carries a
 * {@code companyName}, so two runs of it produce identical canonical keys and the
 * second deduplicates against the first instead of creating anything.
 *
 * <p>So the leftovers have to actually go. This deletes them scoped to one
 * source, which is deliberately narrower than resetting the database: a blanket
 * truncation would also remove the seeded skills and institutions other tests
 * depend on, and — worse — would hide a real defect by making any amount of
 * stray writing invisible.
 *
 * <p><b>Why this is safe.</b> Every foreign key into {@code job_sources} and
 * {@code jobs} is {@code ON DELETE CASCADE}, verified against the live schema, so
 * two deletes reach every child row without this class having to know the tree.
 * Jobs are removed only when the source being deleted was the last thing
 * observing them, so a job another source still lists is left alone.
 *
 * <p><b>Why it survives the coming refactor.</b> It runs in its own
 * {@code REQUIRES_NEW} transaction and commits. It therefore cleans up data
 * whether the production code joined the test's transaction or committed
 * independently of it — which is the whole point, since the second is what M1 and
 * M2 will introduce.
 */
@Component
public class IngestionTestData {

    private static final Logger log = LoggerFactory.getLogger(IngestionTestData.class);

    private final EntityManager entityManager;
    private final TransactionTemplate ownTransaction;

    public IngestionTestData(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        // Its own transaction, always. Cleanup that joined the caller's
        // transaction would be undone by the same rollback it exists to
        // compensate for.
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Deletes one source and everything ingestion created through it.
     *
     * @return how many jobs were removed as a result, so a caller can assert on it
     */
    public int deleteSource(UUID sourceId) {
        if (sourceId == null) {
            return 0;
        }
        Integer removed = ownTransaction.execute(status -> {
            // Noted before the source goes, because deleting it cascades the
            // observations that name these jobs.
            @SuppressWarnings("unchecked")
            List<UUID> touched = entityManager
                    .createNativeQuery("select distinct job_id from job_observations where source_id = :id")
                    .setParameter("id", sourceId)
                    .getResultList();

            entityManager.createNativeQuery("delete from job_sources where id = :id")
                    .setParameter("id", sourceId)
                    .executeUpdate();

            if (touched.isEmpty()) {
                return 0;
            }
            // Only jobs nothing observes any more. A job another source still
            // lists is somebody else's and stays.
            int deletedJobs = entityManager.createNativeQuery(
                            "delete from jobs j where j.id in (:ids) "
                                    + "and not exists (select 1 from job_observations o where o.job_id = j.id)")
                    .setParameter("ids", touched)
                    .executeUpdate();
            entityManager.flush();
            return deletedJobs;
        });
        int deleted = removed == null ? 0 : removed;
        log.debug("Test cleanup removed source {} and {} orphaned jobs", sourceId, deleted);
        return deleted;
    }

    /**
     * Removes every source whose name starts with a test's own prefix.
     *
     * <p>For a class-level sweep, so a run that stops early or is filtered to a
     * single method still leaves nothing behind. Scoped by an identifier the
     * test chose, never by table.
     */
    public int deleteSourcesNamed(String namePrefix) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = ownTransaction.execute(status -> entityManager
                .createNativeQuery("select id from job_sources where name like :prefix")
                .setParameter("prefix", namePrefix + "%")
                .getResultList());
        if (ids == null) {
            return 0;
        }
        ids.forEach(this::deleteSource);
        return ids.size();
    }

    /** Convenience for a test that created several sources. */
    public void deleteSources(UUID... sourceIds) {
        for (UUID id : sourceIds) {
            deleteSource(id);
        }
    }
}

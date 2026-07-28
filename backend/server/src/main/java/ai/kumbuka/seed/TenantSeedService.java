package ai.kumbuka.seed;

import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.repo.MemoryRepository;
import ai.kumbuka.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * D-CORE-11: plant the protected system-seed mnemonics into the current
 * tenant's scopes.
 *
 * <p>Idempotent — {@link MemoryRepository#seed} short-circuits on an existing
 * {@code (scope, key)} and leaves that row untouched (confirmed by
 * {@code ProtectedSeedTest}); it does NOT promote a pre-existing unprotected
 * row to {@code protected = true}. That promotion lives only on the
 * {@code remember()} upsert path, so re-seeding does not migrate the live
 * johannesbayer how-to entries that exist as ordinary conventions today.
 *
 * <p>The whole batch runs in a single transaction — a partial seed (some
 * entries committed, others not) would be a worse state than no seed.
 */
@TenantBound
@ApplicationScoped
public class TenantSeedService {

    private static final Logger LOG = Logger.getLogger(TenantSeedService.class);

    @Inject MemoryRepository memories;

    public record SeedReport(int planted, int promoted, int unchanged) {}

    @Transactional
    public SeedReport seedCurrentTenant() {
        SeedFixture fx = SeedFixture.v1();
        int planted = 0;
        for (SeedFixture.Entry e : fx.seeds()) {
            MemoryType type = MemoryType.fromDb(e.type());
            // The repo's seed() handles both first-write and re-seed cases.
            // We can't distinguish planted/promoted/unchanged without an
            // extra round-trip — every successful seed is counted as
            // "planted or upgraded".
            memories.seed(e.scope(), type, e.key(), e.content());
            planted++;
        }
        LOG.infof("D-CORE-11 seed: planted=%d (re-runs are idempotent)", planted);
        // promoted/unchanged left at zero — see comment above; field
        // remains in the record for the eventual three-bucket breakdown.
        return new SeedReport(planted, 0, 0);
    }

    /** Number of fixture entries — handy for the endpoint to surface in 200 OK responses. */
    public int fixtureSize() {
        return SeedFixture.v1().seeds().size();
    }

    /** Read-only view of the fixture keys, for verification / probes. */
    public List<String> fixtureKeys() {
        return SeedFixture.v1().seeds().stream().map(SeedFixture.Entry::key).toList();
    }
}

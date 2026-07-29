package ai.kumbuka.domain;

/**
 * The server-derived identity used to author protected system-seed mnemonics
 * Never a real principal — the underscores make it visibly
 * non-human (mirrors {@code __former-member__} for erasure-tombstoned rows).
 *
 * <p>Used exclusively by the provisioning seeder reachable through
 * {@code /api/internal/seed-tenant}. No user-facing surface accepts this
 * value as input; pre-persist checks in {@code MemoryRepository.remember}
 * reject any non-system caller attempting to claim it.
 */
public final class SystemSubject {

    /** The literal value stored in {@code memory.owner_subject} for seed rows. */
    public static final String SENTINEL = "__system__";

    private SystemSubject() {}

    /** True if the given owner_subject identifies the system seeder. */
    public static boolean isSystem(String ownerSubject) {
        return SENTINEL.equals(ownerSubject);
    }
}

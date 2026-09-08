package reikai.domain.merge

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences

/**
 * Source-grouping operations for one content type, backed by [MergeGroupRepository]. One class serves
 * both, with the type fixed at construction by the thin [reikai.domain.manga.MangaMergeManager] /
 * [reikai.domain.novel.NovelMergeManager] subclasses. [ReikaiLibraryPreferences.seriesMergingEnabled]
 * gates resolution: off resolves every entry standalone, preserving groups. [onBeforeDissolve] runs
 * on every path that breaks a group up, since a merged group shares one tracker binding whose copies
 * must be handed out at that moment; it lives here, not at call sites, three of which forgot it.
 */
open class EntryMergeManager(
    private val contentType: ContentType,
    private val repository: MergeGroupRepository,
    private val preferences: ReikaiLibraryPreferences,
    private val onBeforeDissolve: suspend (memberIds: List<Long>) -> Unit,
) : MergeManager {

    private val isNovel: Boolean get() = contentType == ContentType.NOVELS

    /**
     * The group [targetId] belongs to, or just itself when it is ungrouped or merging is disabled.
     *
     * Library members only. Removing an entry from the library keeps its group, so a re-add rejoins
     * it, but the entry must stop feeding what the group shows: its chapters, its counts, its source
     * chip. Every display and aggregation read resolves through here, so that holds everywhere rather
     * than at each call site. Data operations read [MergeGroupRepository.getMembers] instead.
     */
    suspend fun computeRelatedIds(targetId: Long): LongArray {
        if (!preferences.seriesMergingEnabled.get()) return longArrayOf(targetId)
        val groupId = repository.getGroupId(contentType, targetId) ?: return longArrayOf(targetId)
        // A group whose members have all left the library still answers with the target, so a caller
        // resolving an entry it is holding never gets an empty list back.
        return repository.getFavoriteMembers(contentType, groupId)
            .takeIf { it.isNotEmpty() }
            ?.toLongArray()
            ?: longArrayOf(targetId)
    }

    /** [computeRelatedIds] as a `List` for callers (the novel reader / tracking path) that want one. */
    suspend fun relatedIdsList(targetId: Long): List<Long> = computeRelatedIds(targetId).toList()

    /** The group [targetId] is in, or null when it is in none or grouping is switched off. What a
     *  caller reading the group's stored stitch needs, since that is keyed by group rather than entry. */
    suspend fun groupIdOf(targetId: Long): Long? =
        if (preferences.seriesMergingEnabled.get()) repository.getGroupId(contentType, targetId) else null

    /**
     * Fold [ids] into one group. Refused while merging is off: nothing renders the group, and the
     * library's Unmerge only appears for a row the collapse marked, so the write had no undo.
     *
     * Only this verb is gated. Maintaining a group that exists (replace, remove, dissolve, reorder,
     * restore) stays live, because the switch hides grouping rather than discarding it.
     */
    override suspend fun merge(ids: List<Long>) {
        if (!preferences.seriesMergingEnabled.get()) return
        repository.merge(contentType, ids)
    }

    /**
     * Atomically swap [oldId] out of its group and [newId] in (a replace migration); no-op when
     * [oldId] is ungrouped.
     *
     * Usually only membership changes, but migrating onto a sibling of the same group leaves the
     * target alone and really does break the group up, so the hook is handed to the repository. It
     * has to run inside that transaction, while the departing entry is still favorited.
     */
    suspend fun replaceInGroup(oldId: Long, newId: Long) {
        repository.replaceInGroup(contentType, oldId, newId, onBeforeDissolve)
    }

    /**
     * What an exact undo of a split needs, read before the split runs: a group that shrinks below two
     * members is deleted outright and takes its ranking with it, so the flag cannot be recovered
     * afterwards. Empty for an ungrouped entry.
     */
    override suspend fun captureGroup(anchorId: Long): GroupSnapshot {
        val groupId = repository.getGroupId(contentType, anchorId) ?: return GroupSnapshot.EMPTY
        return GroupSnapshot(
            orderedMemberIds = repository.getMembers(contentType, groupId),
            overrideSourceRanking = repository.getGroup(groupId)?.overrideSourceRanking == true,
        )
    }

    /** Put a captured group back exactly: same members, same order, same ranking. */
    override suspend fun restoreGroup(snapshot: GroupSnapshot) {
        repository.materializeGroup(contentType, snapshot.orderedMemberIds, snapshot.overrideSourceRanking)
    }

    override fun membershipChanges(): Flow<Map<Long, Long>> =
        repository.getAllMembershipsAsFlow(contentType)

    /**
     * Split [targetIds] out of their group, keeping the survivors grouped; the group is dissolved when
     * fewer than two members remain (the "remove all sources" case). Returns the surviving ids.
     */
    override suspend fun removeFromGroup(relatedIds: LongArray, targetIds: List<Long>): LongArray {
        if (targetIds.isEmpty()) return relatedIds
        onBeforeDissolve(relatedIds.toList())
        return repository.removeFromGroup(contentType, targetIds).toLongArray()
    }

    /**
     * Whether the add-time duplicate dialog offers grouping (the picker and the "add to existing group"
     * action). Needs the same-title suggestion pref and the master switch: with grouping switched off
     * every series resolves standalone, so offering to group would build a group nothing displays.
     */
    val suggestGroupingOnAdd: Boolean
        get() = preferences.seriesMergingEnabled.get() && sameTitlePreference.get()

    private val sameTitlePreference
        get() = if (isNovel) preferences.novelAutoMergeSameTitle else preferences.autoMergeSameTitle

    /**
     * Group id per id in [ids] for callers rendering group-collapsed cards (the add-time duplicate
     * dialog). Ungrouped ids are absent. One batch query rather than a read per id. Empty when merging is
     * disabled, so nothing collapses while groups are hidden, matching every other resolution path.
     */
    suspend fun groupIdsFor(ids: List<Long>): Map<Long, Long> {
        if (!preferences.seriesMergingEnabled.get()) return emptyMap()
        val memberships = repository.getAllMemberships(contentType)
        return ids.mapNotNull { id -> memberships[id]?.let { id to it } }.toMap()
    }

    /**
     * The group of [anchorId] in its own priority order when the group overrides the global source
     * ranking, else empty (aggregation then falls back to the global list). This is the per-group
     * override channel: aggregation ranks members by this order directly, so two members sharing a
     * source still order distinctly (a source-id list could not tell them apart).
     */
    suspend fun overrideRankingMemberIds(anchorId: Long): List<Long> {
        val groupId = repository.getGroupId(contentType, anchorId) ?: return emptyList()
        if (repository.getGroup(groupId)?.overrideSourceRanking != true) return emptyList()
        return repository.getMembers(contentType, groupId)
    }

    /** Persist [orderedMemberIds] as the group's source order (0 = trunk) and turn the override on. The
     *  ids are the manage-sources rows after a drag; the group is resolved from the first of them. */
    override suspend fun setSourceOrder(orderedMemberIds: List<Long>) {
        val groupId = orderedMemberIds.firstNotNullOfOrNull { repository.getGroupId(contentType, it) } ?: return
        repository.setSourceOrder(contentType, groupId, orderedMemberIds)
    }

    /** Clear the per-group override for [anchorId]'s group (back to the global ranking). */
    override suspend fun clearSourceOrder(anchorId: Long) {
        val groupId = repository.getGroupId(contentType, anchorId) ?: return
        repository.clearSourceOrder(contentType, groupId)
    }

    /**
     * Hand each member of [entryIds]' groups its own copy of the shared tracker binding, for entries
     * about to leave the library while their group stays.
     *
     * Must run BEFORE the unfavorite: the hand-out skips non-favorites, so afterwards the leaving
     * entry is precisely who it misses. No-op with merging off, where nothing was shared to hand out.
     */
    suspend fun handOutTrackersBeforeRemoval(entryIds: List<Long>) {
        if (!preferences.seriesMergingEnabled.get()) return
        val handled = HashSet<Long>()
        entryIds.distinct().forEach { id ->
            val groupId = repository.getGroupId(contentType, id) ?: return@forEach
            if (!handled.add(groupId)) return@forEach
            onBeforeDissolve(repository.getFavoriteMembers(contentType, groupId))
        }
    }

    /** Fully dissolve the group of each of [targetIds] (the library bulk "Unmerge"). Ungrouped targets
     *  are skipped. */
    suspend fun unmerge(targetIds: List<Long>) {
        targetIds.distinct().forEach { targetId ->
            onBeforeDissolve(computeRelatedIds(targetId).toList())
            repository.dissolve(contentType, targetId)
        }
    }

    /** Dissolve every group of this content type (the Settings "Clear all merges" action). */
    suspend fun clearAllMergesIncludingAuto() {
        repository.getAllMemberships(contentType)
            .entries.groupBy({ it.value }, { it.key })
            .values.forEach { members -> onBeforeDissolve(members) }
        repository.clearAll(contentType)
    }
}

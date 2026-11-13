package com.spektralab.android.core.model

/** Metadata only. Neither assets nor virtual copies contain decoded image planes. */
internal enum class SourceAvailability { AVAILABLE, MISSING, PERMISSION_DENIED, UNAVAILABLE }

internal data class SourceEvidence(
    val uri: String,
    val sourceKey: String,
    val authority: String,
    val documentId: String?,
    val mediaId: Long?,
    val displayName: String,
    val mime: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val sha256: String?,
    val availability: SourceAvailability,
) {
    val verified: Boolean get() = availability == SourceAvailability.AVAILABLE && size > 0 &&
        sha256?.matches(Regex("[a-f0-9]{64}")) == true
    val revision: String get() = CanonicalJson.fingerprint("source-v1:$sha256:$size:$mime:$width:$height:$orientation")
}

internal data class LegacySourceBinding(val legacyKey: String, val source: SourceEvidence)
internal enum class MigrationState { MIGRATED, PENDING_SOURCE, AMBIGUOUS, INVALID, SOURCE_CHANGED }
internal enum class LibraryEditSupport { LEGACY, PRESERVED_READ_ONLY }
internal data class LibraryVariant(
    val id: String,
    val assetId: String,
    val isDefault: Boolean,
    val paramsJson: String,
    val revision: Long,
    val editSchemaVersion: Int,
    val renderVersion: String,
    val support: LibraryEditSupport,
)
internal data class MigrationRecord(
    val legacyKey: String,
    val state: MigrationState,
    val payloadSha256: String,
    val variantId: String?,
)
internal data class MigratedSession(val id: String, val currentPosition: Int?, val slots: List<Pair<String, String?>>)

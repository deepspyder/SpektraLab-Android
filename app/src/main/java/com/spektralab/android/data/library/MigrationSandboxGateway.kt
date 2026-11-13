package com.spektralab.android.data.library

import com.spektralab.android.core.model.LibraryVariant

/** Proves authority routing against copies before the real editor is allowed to migrate. */
internal class MigrationSandboxGateway(private val repository: LibraryPrototypeRepository, private val archive: LegacyMigrationArchive) {
    fun read(legacyKey: String): String? = repository.migratedVariant(legacyKey)?.paramsJson
        ?: archive.entries.firstOrNull { it.key == legacyKey }?.let(archive::payload)

    fun save(legacyKey: String, expectedRevision: Long, paramsJson: String): LibraryVariant {
        val migrated = checkNotNull(repository.migratedVariant(legacyKey)) { "SOURCE_NOT_MIGRATED" }
        return repository.saveVariant(migrated.id, expectedRevision, paramsJson)
    }
}

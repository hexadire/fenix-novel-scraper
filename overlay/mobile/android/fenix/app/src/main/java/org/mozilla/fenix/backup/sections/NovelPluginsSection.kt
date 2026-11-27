/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.backup.sections

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.mozilla.fenix.R
import org.mozilla.fenix.backup.BackupSection
import org.mozilla.fenix.backup.SectionReader
import org.mozilla.fenix.backup.SectionWriter
import org.mozilla.fenix.backup.bool
import org.mozilla.fenix.backup.long
import org.mozilla.fenix.backup.string
import org.mozilla.fenix.novel.NovelPluginStore

/**
 * The plugins of the novel scraper that were imported or made in the app (Settings > Novel scraper > Plugins), with the
 * JSON of each one and whether it is switched on. A plugin that is there already is not added again, and a plugin that
 * was switched off in the backup is switched off after it is restored.
 *
 * Not part of this section: the downloaded chapters of the library (large, and can be downloaded again), and the options
 * of the scraper.
 */
internal class NovelPluginsSection(private val context: Context) : BackupSection {
    override val id = "novel_plugins"
    override val version = 2
    override val titleRes = R.string.backup_section_novel_plugins

    override suspend fun export(out: SectionWriter) {
        for (entry in NovelPluginStore(context).listImported()) {
            out.write(
                buildJsonObject {
                    put("name", entry.plugin.name)
                    put("enabled", entry.enabled)
                    put("addedAt", entry.addedAt)
                    put("source", entry.plugin.source)
                },
            )
        }
    }

    override suspend fun import(input: SectionReader) {
        val store = NovelPluginStore(context)
        for (line in input.entries()) {
            val source = line.string("source")
            if (source.isNullOrBlank()) {
                input.skipped()
                continue
            }
            val name = line.string("name")
            when (val result = store.add(source, line.long("addedAt") ?: System.currentTimeMillis())) {
                is NovelPluginStore.AddResult.Added -> {
                    if (line.bool("enabled") == false) store.setEnabled(result.entry.plugin.id, false)
                    input.restored()
                }
                is NovelPluginStore.AddResult.Duplicate -> input.skipped()
                is NovelPluginStore.AddResult.Invalid -> {
                    input.skipped()
                    input.warn("A plugin could not be restored: " + (name ?: "unnamed") + " (" + result.message + ")")
                }
            }
        }
    }
}

/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novelscraper

import android.content.Context
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * The novel scraper: a built-in extension (the engine of WebToEpub, in `assets/extensions/novel_scraper`) that runs
 * inside the browser engine, and this package, which gives it the plugins the person imported.
 *
 * The extension appears in the Extensions part of the main menu as "Novel Scraper". Its page opens in a tab and
 * reads the page that was open. See `CLAUDE.md` in the root of the repository for how the parts fit together.
 */
internal object NovelScraper {
    /** Must be the same as the id in `assets/extensions/novel_scraper/manifest.json`. */
    const val EXTENSION_ID = "novel-scraper@fenixfork.local"
    const val EXTENSION_URL = "resource://android/assets/extensions/novel_scraper/"

    /** Must be the same as `APP` in `assets/extensions/novel_scraper/background.js`. */
    const val MESSAGE_NAME = "fenixForkNovelPlugins"

    private val logger = Logger("NovelScraper")

    /**
     * Installs the extension (it is kept between runs; installing again only updates it) and connects it to the store
     * of plugins. Called once when the engine is made.
     */
    fun install(engine: Engine, context: Context) {
        val app = context.applicationContext
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_URL,
            onSuccess = { extension ->
                extension.registerBackgroundMessageHandler(MESSAGE_NAME, NovelScraperBridge(NovelPluginStore(app)))
                logger.debug("The novel scraper is installed.")
            },
            onError = { throwable ->
                logger.error("The novel scraper could not be installed.", throwable)
            },
        )
    }
}

/**
 * Answers the questions of the background page of the extension. There is one question: `{ "type": "list" }`, answered
 * with the enabled and disabled plugins, the code included:
 * `{ "ok": true, "plugins": [{ "id", "name", "enabled", "hosts": [...], "source" }] }`.
 */
internal class NovelScraperBridge(private val store: NovelPluginStore) : MessageHandler {
    override fun onMessage(message: Any, source: EngineSession?): Any? {
        val type = (message as? JSONObject)?.optString("type")
        return when (type) {
            "list" -> listReply()
            else -> JSONObject().put("ok", false).put("error", "Unknown request.")
        }
    }

    private fun listReply(): JSONObject = try {
        val plugins = JSONArray()
        for (plugin in store.list()) {
            plugins.put(
                JSONObject()
                    .put("id", plugin.id)
                    .put("name", plugin.name)
                    .put("enabled", plugin.enabled)
                    .put("hosts", JSONArray(plugin.hosts))
                    .put("source", plugin.source),
            )
        }
        JSONObject().put("ok", true).put("plugins", plugins)
    } catch (e: SecurityException) {
        JSONObject().put("ok", false).put("error", "The plugins could not be read.")
    }
}

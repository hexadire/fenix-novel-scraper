/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.novel

import mozilla.components.concept.engine.Engine

/**
 * An earlier version of the novel scraper was a built-in extension (a page of WebToEpub in a tab). The scraper is part of the
 * app now, so that extension is removed from the browser if it is still installed.
 */
object NovelExtensionCleanup {
    private const val OLD_EXTENSION_ID = "novel-scraper@fenixfork.local"

    fun run(engine: Engine) {
        engine.listInstalledWebExtensions(
            onSuccess = { extensions ->
                for (extension in extensions) {
                    if (extension.id == OLD_EXTENSION_ID) engine.uninstallWebExtension(extension)
                }
            },
            onError = { },
        )
    }
}

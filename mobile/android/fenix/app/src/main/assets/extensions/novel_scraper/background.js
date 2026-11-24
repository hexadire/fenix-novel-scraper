/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/* globals browser */

"use strict";

// The other side of this conversation is NovelScraperBridge in
// app/src/main/java/org/mozilla/fenix/novelscraper/NovelScraper.kt.
//
// The scraper page (popup.html) cannot talk to the app itself, so it asks this background page, which asks the app:
//   page -> { type: "novelPlugins.list" } -> background -> sendNativeMessage -> app
// The app answers { ok: true, plugins: [{ id, name, enabled, hosts: [...], source }] }.
// The last answer is kept in storage, so the plugins still load when the app does not answer (for example while it
// is starting).

// This must be the same name as MESSAGE_NAME in NovelScraper.kt.
const APP = "fenixForkNovelPlugins";
const CACHE_KEY = "novelPluginsCache";

function describe(error) {
    return String((error && error.message) || error || "unknown error").slice(0, 200);
}

async function readCache() {
    try {
        const stored = await browser.storage.local.get(CACHE_KEY);
        return Array.isArray(stored[CACHE_KEY]) ? stored[CACHE_KEY] : null;
    } catch (e) {
        return null;
    }
}

async function listPlugins() {
    let lastError = null;
    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            const reply = await browser.runtime.sendNativeMessage(APP, { type: "list" });
            if (reply && reply.ok && Array.isArray(reply.plugins)) {
                try {
                    await browser.storage.local.set({ [CACHE_KEY]: reply.plugins });
                } catch (e) {
                    // Only the cache is lost.
                }
                return { ok: true, plugins: reply.plugins, from: "app" };
            }
            lastError = "The app sent an answer that cannot be used.";
        } catch (e) {
            lastError = describe(e);
        }
        await new Promise(resolve => setTimeout(resolve, 400));
    }
    const cached = await readCache();
    return {
        ok: cached !== null,
        plugins: cached || [],
        from: "cache",
        error: lastError,
    };
}

browser.runtime.onMessage.addListener(message => {
    if (message && message.type === "novelPlugins.list") {
        return listPlugins();
    }
    // Not for this listener (the page that reads the scraped page listens to its own messages).
    return undefined;
});

/*
    Loads the plugins that the person imported in the app (Settings > Novel scraper plugins).

    A plugin is a JavaScript file in the same format as the files in js/parsers/: a class that extends Parser,
    registered with parserFactory.register("host", () => new XParser()). The app keeps the files. This page asks for them
    when it opens (through background.js, which talks to the app) and runs them, so that parserFactory knows the sites
    of the plugins as well as the sites that are built in.

    A plugin of an imported file wins over a built in parser for the same host, so a broken parser can be replaced.
*/
"use strict";

class NovelPlugins {
    /** What happened to each plugin the app handed over: { id, name, ok, hosts, error }. */
    static results = [];

    /** Where the list came from: "app", "cache" or "none". */
    static listSource = "none";

    static async loadAll() {
        NovelPlugins.results = [];
        let reply;
        try {
            reply = await browser.runtime.sendMessage({ type: "novelPlugins.list" });
        } catch (e) {
            reply = { ok: false, plugins: [], from: "none", error: NovelPlugins.describe(e) };
        }
        reply = reply || { ok: false, plugins: [], from: "none", error: "No answer from the app." };
        NovelPlugins.listSource = reply.from || "none";

        for (let plugin of (reply.plugins || [])) {
            if (plugin == null || plugin.enabled === false) {
                continue;
            }
            NovelPlugins.results.push(NovelPlugins.run(plugin));
        }
        NovelPlugins.showStatus(reply);
    }

    static run(plugin) {
        let result = { id: plugin.id, name: plugin.name || plugin.id, ok: false, hosts: [], error: null };
        let registeredHosts = [];

        // A plugin for a site that already has a parser replaces that parser instead of failing.
        parserFactory.register = function(hostName, constructor) {
            registeredHosts.push(hostName);
            parserFactory.reregister(hostName, constructor);
        };
        try {
            // Indirect eval: the code runs in the global scope, where Parser, parserFactory, util and HttpClient are.
            // The extension's content security policy allows this (see manifest.json).
            (0, eval)(String(plugin.source) + "\n//# sourceURL=novel-plugin-" + encodeURIComponent(result.name));
            result.ok = true;
        } catch (e) {
            result.error = NovelPlugins.describe(e);
        } finally {
            // Takes the replacement away, so the normal method of the class is used again.
            delete parserFactory.register;
        }
        result.hosts = registeredHosts;
        return result;
    }

    static describe(error) {
        return String((error && error.message) || error || "unknown error").slice(0, 300);
    }

    /** True when a plugin that the person imported handles this URL (as opposed to a built in parser). */
    static handlesHost(hostName) {
        return NovelPlugins.results.some(r => r.ok && r.hosts.some(h => ParserFactory.stripLeadingWww(h) === hostName));
    }

    static showStatus(reply) {
        let failed = NovelPlugins.results.filter(r => !r.ok);
        let loaded = NovelPlugins.results.length - failed.length;
        if (NovelPlugins.results.length === 0 && reply.ok !== false) {
            return;
        }
        let container = document.querySelector(".container");
        if (container == null) {
            return;
        }
        let box = document.getElementById("novelPluginStatus");
        if (box == null) {
            box = document.createElement("div");
            box.id = "novelPluginStatus";
            container.insertBefore(box, container.firstChild);
        }
        let lines = [];
        if (loaded > 0) {
            lines.push("Imported plugins loaded: " + loaded);
        }
        for (let f of failed) {
            lines.push("Plugin \"" + f.name + "\" did not load: " + f.error);
        }
        if (reply.ok === false) {
            lines.push("Could not read the imported plugins from the app" + (reply.error ? " (" + reply.error + ")" : "") + ".");
        } else if (NovelPlugins.listSource === "cache") {
            lines.push("The app did not answer. The plugins from the last time were used.");
        }
        box.textContent = lines.join("\n");
        box.className = (failed.length > 0 || reply.ok === false) ? "novelStatusBad" : "novelStatusGood";
        box.hidden = lines.length === 0;
    }
}

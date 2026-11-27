# Notes for working on this fork

This repository is a personal fork of Firefox for Android (Fenix) that is built by GitHub Actions
(`.github/workflows/build-debug-apk.yml`) and installed by hand. These notes are for whoever changes it next, human or
AI. They list the things that are easy to break and expensive to find out about.

## Installing updates

The APK from a build must install **over** the app that is already on the phone, so that settings, logins, tabs and
everything else survive. Android only allows that when all of these hold:

1. **Same package name.** In this repository `applicationId` is `dev.personal.novelscraper` (set by
   `.github/scripts/apply_overlay.py`, `NEW_APPLICATION_ID`) plus the `.fenix.debug` suffix of the debug build type, in
   `mobile/android/fenix/app/build.gradle`: the app is installed as `dev.personal.novelscraper.fenix.debug`, a separate
   app from the other Fenix builds (the label under its icon, "Novel Scraper", is in
   `app/src/debug/res/values/novel_scraper_app_name.xml`). Never change either.
2. **Same signing key.** Every build is signed with `.github/keys/debug.keystore` (the workflow copies it to
   `~/.android/debug.keystore`). Never replace or regenerate it, and never sign debug builds with another key.
3. **A versionCode that is not lower than the installed one.** The debug `versionCode` is
   `forkDebugVersionCode()` in `mobile/android/fenix/app/build.gradle`: `2050000000` plus the minutes since
   2026-01-01 UTC. It only ever goes up, whatever runs the build (CI, a re-run, another workflow, another repository, a
   local build). **Never lower it and never switch to a scheme that can produce smaller numbers**, such as a CI run
   number, a counter or a constant. Android's maximum is 2100000000, which this scheme reaches around the year 2121.

(The `build.gradle` this repository is built from takes the debug `versionCode` from `GITHUB_RUN_NUMBER`, which only
goes up as well; the scheme described next is not in it.)

`Verify APK signature and version code` in the workflow checks 2 and 3 on every APK it builds, writes a table into the
job summary, and fails the job when an APK would not install as an update. If the scheme in `build.gradle` ever has to
change, change `MIN_VERSION_CODE` in the workflow with it, and keep the new numbers above every number used before.

If a phone shows "App not installed" for an update anyway, the installed copy was made with another key or with a
higher versionCode than the build has. The copy has to be uninstalled once (this erases its data) after which all
later builds update normally. If the installed copy still runs and has the backup feature (see below), make a backup
first and restore it after the reinstall. The APKs of a build are split by CPU: install the `arm64-v8a` one on almost
every phone.

## Backup and restore

The app can save all of its data to one file and restore it from such a file (Settings → Backup and restore). The code
is in `mobile/android/fenix/app/src/main/java/org/mozilla/fenix/backup/`; the `README.md` there explains how the file
and the code are laid out. **When you add or change something that keeps data the person would miss after a reinstall,
keep the backup in step with it, in the same change.** What is saved is exactly what `BackupSections.kt` lists.

What to do depends on where the data lives:

- **A new setting in a `SharedPreferences` file that is saved already** (`fenix_preferences`, the default preferences,
  `custom_autocomplete`, the reader view file, the language, IP protection): nothing. Every key of these files is saved,
  including keys added later. Only when the setting means something for one installation or device only (an identifier,
  a counter, a token, a path, account state, a cache, a developer option), add its name or a prefix to
  `DeviceSpecificPreferences` in `sections/SettingsSection.kt`.
- **A new `SharedPreferences` file**: add a `PreferencesFile` there and list it in the default `files` of
  `SettingsSection`. The name inside the backup never changes, even if the file on disk is renamed.
- **A new Preferences DataStore**: add a `PreferencesDataStoreSection` to `BackupSections.all()`, with `Style.SETTINGS`
  for choices of the person and `Style.STATE` for what the app collects while it is used.
- **A database, files, or anything else**: write a `BackupSection` in `backup/sections/`, add it to
  `BackupSections.all()` after the sections it refers to (the order is the order of saving and restoring), and add its
  title as `backup_section_<id>` to `res/values/backup_strings.xml`. Set `sensitive = true` when it holds secrets (the
  screen then asks for a passphrase).
- **Data that is left out on purpose**: say so in the comment of `BackupSections` and in the `README.md`, and tell the
  person. Today that is the Firefox account, add-ons, site permissions, downloads, credit cards and addresses, private
  and recently closed tabs, wallpapers, and what websites store themselves except cookies.

Rules that keep old backups working. They matter more than any single feature, because a backup is only worth anything
when it can still be restored a year from now:

1. **Never change the `id` of a section, the name of a field in its lines, or the name a preference file has inside the
   backup.** Stop writing a field instead of renaming it.
2. **Every version of the app reads every older backup.** When the lines of a section change in a way old code could
   not understand, raise that section's `version` and keep `import` able to read the old lines
   (`SectionReader.sectionVersion`). Raise `BackupContainer.FORMAT_VERSION` or `BackupArchive.ARCHIVE_FORMAT` only when
   the layout of the file itself changes.
3. **Restoring only adds.** It must never delete or overwrite what the person has (settings are the exception: the
   backup wins). Restoring the same backup twice must change nothing the second time. One failing section must not stop
   the others: throw for a section-wide problem, `SectionWriter.warn` or `SectionReader.skipped` for single items.
4. **No secrets in messages.** Warnings and errors show up on screen; name the website or the setting, never a password,
   cookie value or other content.
5. **The cookies are read and written by a built-in extension** (`assets/extensions/backup_cookies/`). Keep both sides
   in step: `MESSAGE_NAME`, `EXTENSION_ID` (also in `manifest.json`) and the list of cookie fields (`COOKIE_FIELDS` in
   `CookiesSection.kt`, `toBackupCookie` in `background.js`).
6. **Mozilla's own files stay as they are, as far as possible.** The feature adds only new files and three small edits
   to existing ones: the `BackupActivity` entry in `AndroidManifest.xml`, the "Backup and restore" entry in
   `res/xml/preferences.xml`, and its click handler in `settings/SettingsFragment.kt`. Its texts are in their own
   `backup_strings.xml`. If upstream changes are merged in, keep those three edits. (The novel scraper below adds
   one section, `NovelPluginsSection`, and one string, `backup_section_novel_plugins`, to the backup.)

Before handing over a change to the backup, check it the way `README.md` says (a plain JVM for the logic, a phone for the
rest), and update `README.md` when the list of sections or the rules change.

## Novel scraper

The browser can download web novels as EPUB files. It is part of the app, written in Kotlin (package
`org.mozilla.fenix.novel`), with its own screens, its own engine and its own plugin format. WebToEpub (the browser extension)
was the model for the features, not part of the code.

**Where it is in the app**
- Main menu → **Download novel** (the item `NovelScraperMenuItem`, in the tools group of the main menu): opens the scraper with the
  page that is open.
- Settings → **Novel scraper**; and Share → the app, which opens it with the shared address.
- Screens (`NovelScraperActivity`): the main screen (address, details, download), **Chapters** (select, rename, reverse, filter,
  range, edit/copy addresses), **Library**, **Plugins** and **Options**. All of them are made in code, like the backup screen.

**How it works**
- `NovelEngine` reads the page of a novel and its chapters with Jsoup. Pages are fetched through the browser's own network client
  (`components.core.client`, `NovelFetcher`), so the cookies of the tabs are used and a site that was opened in the browser first
  (to pass a check) can be read.
- A **plugin** (`SitePlugin`, JSON, described in `assets/novel/plugin_guide.md`) says how to read one site: CSS selectors for
  the details, the chapter list (several pages, or a JSON API) and the text, plus what to remove. Everything a plugin leaves out is
  found by automatic detection (`NovelDetector`). 267 plugins come with the app (`assets/novel/builtin_plugins.json`, made from the
  simple parsers of WebToEpub by `tools/convert_parsers.py`; 89 of them also know the chapter list, the others only the text).
  Imported plugins (`NovelPluginStore`, JSON files in `filesDir/novel_plugins_json/`) win over built-in ones.
- **No plugin for the site?** After reading the page, the app asks whether to create one (`ScraperScreen.offerPlugin`). When the person
  agrees, `NovelClaudeRequest` reads the first chapter and writes one Markdown file with the task, the rules, the shortened HTML of the
  table of contents page and of chapter 1, and the plugin format. The person gives it to Claude and imports the JSON that comes back
  under Plugins (a pasted chat answer works). "Site settings" (`SiteSettingsDialog`) makes a plugin from selectors typed by hand.
- `NovelJob` downloads the chapters one by one into the **library** (`NovelLibrary`, `filesDir/novel_library/`: the text of every
  chapter and the images), so a stopped download continues and new chapters can be added later; then `EpubWriter` writes the EPUB
  (version 3 or 2) to the place the person picked (Storage Access Framework, no storage permission).

**Things that are easy to break**
1. The application id is `dev.personal.novelscraper` (+ `.fenix.debug`): see rule 1 of the backup section above; it is set by
   `.github/scripts/apply_overlay.py` (`NEW_APPLICATION_ID`). Never change it.
2. `implementation libs.jsoup` is added to `app/build.gradle` by the script (Jsoup is in the version catalog already).
3. Plugins run no JavaScript. A site that fills its page by scripts needs `toc.api` / `chapter.api` in its plugin.
4. The backup saves the imported plugins (`NovelPluginsSection`); it does not save the library, the options or which built-in plugins
   were switched off.
5. `NovelExtensionCleanup` (called from `Core.kt`) removes the first version of the scraper, a built-in extension, from browsers that
   still have it. It can be taken out when no such install is left.

**How the build is put together.** This repository keeps the novel scraper in `overlay/` (the same paths as in the Fenix fork). The
workflow uses the Fenix source of this repository, or `fenix-source.zip` in its root, or, when set, the repository named in
`BASE_REPO` (top of `.github/workflows/build-debug-apk.yml`). It then runs `.github/scripts/apply_overlay.py` (removes what the old
extension version left, copies `overlay/`, and makes the few small edits to Mozilla's files, every edit by anchor and safe to repeat:
`app/build.gradle`, `Core.kt`, `SettingsFragment.kt`, `preferences.xml`, `AndroidManifest.xml`, `MainMenu.kt`, `BackupSections.kt`)
and `.github/scripts/english_only.py` (removes the translations for this build), and builds as before. When the fork changes one of
the edited files and an anchor is gone, the script stops and names the file.

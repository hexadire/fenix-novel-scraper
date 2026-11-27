#!/usr/bin/env python3
"""Adds the novel scraper to a checkout of the Fenix fork.

usage: apply_overlay.py <folder with overlay/> <folder with the Fenix fork>
(both can be the same folder)

The novel scraper is native code of the app: Kotlin classes, a few resources and assets. This repository keeps them in
overlay/ (the same paths as in the Fenix fork). The script
  1. removes what an earlier version of the novel scraper (a built-in extension) left in the Fenix fork,
  2. copies overlay/ over the Fenix fork, and
  3. makes the few small edits to existing files of the fork: the entry in Settings, the activity, the item in the main
     menu, the removal of the old extension at start-up, the backup section, a library (Jsoup) and the application id.
Every edit looks for its place by a short anchor and does nothing when it is made already, so running the script twice is
harmless. When an anchor is gone (the fork changed), the script stops and says which file.
"""
import pathlib
import re
import shutil
import sys

FENIX = "mobile/android/fenix/app/src/main"
JAVA = FENIX + "/java/org/mozilla/fenix"

# What the first version of the novel scraper (an extension) put into the fork: it is removed.
OLD_PATHS = [
    FENIX + "/assets/extensions/novel_scraper",
    JAVA + "/novelscraper",
]

NEW_APPLICATION_ID = "dev.personal.novelscraper"


def fail(message):
    print("::error::" + message)
    sys.exit(1)


# region Helpers

def add_import(text, line):
    """Puts an import line among the imports of org.mozilla.fenix, in alphabetical place."""
    if line in text:
        return text
    lines = text.split("\n")
    fenix = [i for i, l in enumerate(lines) if l.startswith("import org.mozilla.fenix.")]
    if not fenix:
        fail("No imports of org.mozilla.fenix found to put '" + line + "' next to.")
    before = [i for i in fenix if lines[i] < line]
    lines.insert((before[-1] + 1) if before else fenix[0], line)
    return "\n".join(lines)


def remove_line(text, needle):
    return "\n".join(l for l in text.split("\n") if needle not in l)


def element_bounds(text, key_anchor, what):
    """The start of the line and the end of the XML element (self-closing) that contains key_anchor."""
    at = text.find(key_anchor)
    if at < 0:
        return None
    start = text.rfind("<", 0, at)
    line_start = text.rfind("\n", 0, start) + 1
    end = text.find("/>", at)
    if end < 0:
        fail(what + ": the element around " + key_anchor + " is not self-closing.")
    return line_start, start, end + 2


def insert_after_element(text, key_anchor, new_element, what):
    bounds = element_bounds(text, key_anchor, what)
    if bounds is None:
        fail(what + ": the anchor " + key_anchor + " was not found.")
    line_start, start, end = bounds
    indent = text[line_start:start]
    block = "\n".join((indent + l) if l else l for l in new_element.strip("\n").split("\n"))
    return text[:end] + "\n" + block + text[end:]


def remove_element(text, key_anchor, what):
    bounds = element_bounds(text, key_anchor, what)
    if bounds is None:
        return text
    line_start, _, end = bounds
    end_of_line = text.find("\n", end)
    end_of_line = len(text) if end_of_line < 0 else end_of_line + 1
    return text[:line_start] + text[end_of_line:]


def remove_kotlin_branch(text, anchor):
    """Removes a `when` branch `anchor -> { ... }` (its closing brace is on its own line at the same indent)."""
    start = text.find(anchor)
    if start < 0:
        return text
    line_start = text.rfind("\n", 0, start) + 1
    indent = text[line_start:start]
    end_marker = "\n" + indent + "}\n"
    end = text.find(end_marker, start)
    if end < 0:
        fail("The end of the branch '" + anchor + "' was not found.")
    return text[:line_start] + text[end + len(end_marker):]

# endregion

# region Edits


def edit_build_gradle(text):
    pattern = re.compile(r'^([ \t]*)applicationId[ \t]+"([^"]*)"', re.M)
    match = pattern.search(text)
    if not match:
        fail("app/build.gradle: 'applicationId \"...\"' was not found.")
    if match.group(2) != NEW_APPLICATION_ID:
        text = (text[:match.start()] + match.group(1) + '// Novel scraper: its own app, next to the other Fenix builds (was "'
                + match.group(2) + '").\n' + match.group(1) + 'applicationId "' + NEW_APPLICATION_ID + '"' + text[match.end():])
    if "libs.jsoup" not in text:
        dependencies = re.compile(r"^dependencies \{\n", re.M)
        if not dependencies.search(text):
            fail("app/build.gradle: the 'dependencies {' block was not found.")
        text = dependencies.sub(lambda m: m.group(0) + "    implementation libs.jsoup // the novel scraper reads web pages with it\n", text, count=1)
    return text


def edit_core(text):
    # Remove what the first version of the novel scraper added.
    text = remove_line(text, "NovelScraper.install(it, context)")
    text = remove_line(text, "// The novel scraper: a built-in extension")
    text = remove_line(text, "import org.mozilla.fenix.novelscraper.NovelScraper")
    if "NovelExtensionCleanup.run" in text:
        return text
    pattern = re.compile(r"^([ \t]*)WebCompatFeature\.install\(it\)[ \t]*$", re.M)
    if not pattern.search(text):
        fail("Core.kt: 'WebCompatFeature.install(it)' was not found.")
    text = pattern.sub(
        lambda m: m.group(0) + "\n" + m.group(1) + "// The novel scraper is part of the app now: remove its first version, an extension.\n"
        + m.group(1) + "NovelExtensionCleanup.run(it)",
        text, count=1)
    return add_import(text, "import org.mozilla.fenix.novel.NovelExtensionCleanup")


def edit_settings_fragment(text):
    text = remove_kotlin_branch(text, "resources.getString(R.string.pref_key_novel_plugins) -> {")
    text = remove_line(text, "import org.mozilla.fenix.novelscraper.NovelPluginsActivity")
    if "NovelScraperActivity" in text:
        return text
    anchor = "resources.getString(R.string.pref_key_backup_restore) -> {"
    start = text.find(anchor)
    if start < 0:
        fail("SettingsFragment.kt: the 'Backup and restore' click handler was not found.")
    line_start = text.rfind("\n", 0, start) + 1
    indent = text[line_start:start]
    end_marker = "\n" + indent + "}\n"
    end = text.find(end_marker, start)
    if end < 0:
        fail("SettingsFragment.kt: the end of the 'Backup and restore' click handler was not found.")
    end += len(end_marker)
    block = (
        indent + "resources.getString(R.string.pref_key_novel_scraper) -> {\n"
        + indent + "    startActivity(NovelScraperActivity.intentFor(requireContext(), null))\n"
        + indent + "    null\n"
        + indent + "}\n"
    )
    text = text[:end] + block + text[end:]
    return add_import(text, "import org.mozilla.fenix.novel.NovelScraperActivity")


def edit_preferences(text):
    text = remove_element(text, 'android:key="@string/pref_key_novel_plugins"', "preferences.xml")
    if "pref_key_novel_scraper" in text:
        return text
    element = (
        '<androidx.preference.Preference\n'
        '    android:key="@string/pref_key_novel_scraper"\n'
        '    app:iconSpaceReserved="false"\n'
        '    android:title="@string/novel_scraper_settings_title"\n'
        '    android:summary="@string/novel_scraper_settings_summary" />'
    )
    return insert_after_element(text, 'android:key="@string/pref_key_backup_restore"', element, "preferences.xml")


def edit_manifest(text):
    text = remove_element(text, 'android:name=".novelscraper.NovelPluginsActivity"', "AndroidManifest.xml")
    if ".novel.NovelScraperActivity" in text:
        return text
    anchor = 'android:name=".backup.BackupActivity"'
    bounds = element_bounds(text, anchor, "AndroidManifest.xml")
    if bounds is None:
        fail("AndroidManifest.xml: the anchor " + anchor + " was not found.")
    line_start, start, end = bounds
    indent = text[line_start:start]
    element = (
        '<activity\n'
        '    android:name=".novel.NovelScraperActivity"\n'
        '    android:configChanges="keyboard|keyboardHidden|mcc|mnc|orientation|screenSize|layoutDirection|smallestScreenSize|screenLayout|uiMode|locale"\n'
        '    android:exported="true"\n'
        '    android:label="@string/novel_scraper_settings_title"\n'
        '    android:windowSoftInputMode="adjustResize">\n'
        '    <intent-filter>\n'
        '        <action android:name="android.intent.action.SEND" />\n'
        '        <category android:name="android.intent.category.DEFAULT" />\n'
        '        <data android:mimeType="text/plain" />\n'
        '    </intent-filter>\n'
        '</activity>'
    )
    block = "\n".join((indent + l) if l else l for l in element.split("\n"))
    return text[:end] + "\n" + block + text[end:]


def edit_backup_sections(text):
    if "NovelPluginsSection" in text:
        return text
    pattern = re.compile(r"^([ \t]*)CookiesSection\(app\),[ \t]*$", re.M)
    if not pattern.search(text):
        fail("BackupSections.kt: 'CookiesSection(app),' was not found.")
    text = pattern.sub(lambda m: m.group(0) + "\n" + m.group(1) + "NovelPluginsSection(app),", text, count=1)
    return add_import(text, "import org.mozilla.fenix.backup.sections.NovelPluginsSection")


def edit_backup_strings(text):
    """The name of the backup section is in novel_scraper_strings.xml: take it out of here, or it would be defined twice."""
    return re.sub(r'[ \t]*<string name="backup_section_novel_plugins">[^<]*</string>[ \t]*\n?', "", text)


def edit_main_menu(text):
    if "NovelScraperMenuItem()" in text:
        return text
    pattern = re.compile(r"^([ \t]*)onClick = onFindInPageMenuClick,\n([ \t]*)\)\n", re.M)
    if not pattern.search(text):
        fail("MainMenu.kt: the 'Find in page' menu item was not found.")
    return pattern.sub(lambda m: m.group(0) + m.group(2) + "NovelScraperMenuItem()\n", text, count=1)


EDITS = [
    ("mobile/android/fenix/app/build.gradle", edit_build_gradle),
    (JAVA + "/components/Core.kt", edit_core),
    (JAVA + "/settings/SettingsFragment.kt", edit_settings_fragment),
    (FENIX + "/res/xml/preferences.xml", edit_preferences),
    (FENIX + "/AndroidManifest.xml", edit_manifest),
    (JAVA + "/backup/BackupSections.kt", edit_backup_sections),
    (FENIX + "/res/values/backup_strings.xml", edit_backup_strings),
    (JAVA + "/components/menu/compose/MainMenu.kt", edit_main_menu),
]

# endregion


def main():
    if len(sys.argv) != 3:
        fail("usage: apply_overlay.py <folder with overlay/> <Fenix fork>")
    repository = pathlib.Path(sys.argv[1])
    target = pathlib.Path(sys.argv[2])
    overlay = repository / "overlay"
    if not overlay.is_dir():
        fail("There is no overlay/ folder in " + str(repository) + ".")

    for relative in OLD_PATHS:
        path = target / relative
        if path.is_dir():
            shutil.rmtree(path)
            print("removed " + relative)

    shutil.copytree(overlay, target, dirs_exist_ok=True)
    print("copied  overlay/ (" + str(sum(1 for p in overlay.rglob("*") if p.is_file())) + " files)")

    for relative, edit in EDITS:
        path = target / relative
        if not path.is_file():
            fail("The Fenix fork has no " + relative + ".")
        before = path.read_text(encoding="utf-8")
        after = edit(before)
        if after != before:
            path.write_text(after, encoding="utf-8")
            print("edited  " + relative)
        else:
            print("already " + relative)


main()

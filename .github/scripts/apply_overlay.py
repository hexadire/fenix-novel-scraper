#!/usr/bin/env python3
"""Adds the novel scraper to a checkout of the Fenix fork.

usage: apply_overlay.py <folder with the novel scraper files> <folder with the Fenix fork>
(both can be the same folder: then the new files are already in place and only the edits are made)

This repository holds only what the novel scraper adds. The workflow checks out the Fenix fork next to it and runs
this script, which
  1. copies the files that are new (the extension, the Kotlin package, the strings), and
  2. makes the few small edits to existing files of the fork (the menu entry in Settings, the activity, the engine
     start-up, the backup section).
Every edit looks for its place by a short anchor and does nothing when the edit is already there, so running the script
twice is harmless. When an anchor is gone (the fork changed), the script stops and says which file.
"""
import pathlib
import re
import shutil
import sys

FENIX = "mobile/android/fenix/app/src/main"
JAVA = FENIX + "/java/org/mozilla/fenix"

# New files and folders: copied as they are.
COPY = [
    FENIX + "/assets/extensions/novel_scraper",
    JAVA + "/novelscraper",
    JAVA + "/backup/sections/NovelPluginsSection.kt",
    FENIX + "/res/values/novel_scraper_strings.xml",
    "mobile/android/fenix/app/src/debug/res/values/novel_scraper_app_name.xml",
]


def fail(message):
    print("::error::" + message)
    sys.exit(1)


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


def edit_core(text):
    if "NovelScraper.install" in text:
        return text
    pattern = re.compile(r"^([ \t]*)WebCompatFeature\.install\(it\)[ \t]*$", re.M)
    if not pattern.search(text):
        fail("Core.kt: 'WebCompatFeature.install(it)' was not found.")
    text = pattern.sub(
        lambda m: m.group(0) + "\n" + m.group(1) + "// The novel scraper: a built-in extension that reads novels (see CLAUDE.md).\n"
        + m.group(1) + "NovelScraper.install(it, context)",
        text, count=1)
    return add_import(text, "import org.mozilla.fenix.novelscraper.NovelScraper")


def edit_settings_fragment(text):
    if "NovelPluginsActivity" in text:
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
        indent + "resources.getString(R.string.pref_key_novel_plugins) -> {\n"
        + indent + "    startActivity(Intent(requireContext(), NovelPluginsActivity::class.java))\n"
        + indent + "    null\n"
        + indent + "}\n"
    )
    text = text[:end] + block + text[end:]
    return add_import(text, "import org.mozilla.fenix.novelscraper.NovelPluginsActivity")


def insert_after_element(text, key_anchor, new_element, what):
    """Inserts an XML element after the self-closing element that contains key_anchor."""
    at = text.find(key_anchor)
    if at < 0:
        fail(what + ": the anchor " + key_anchor + " was not found.")
    start = text.rfind("<", 0, at)
    line_start = text.rfind("\n", 0, start) + 1
    indent = text[line_start:start]
    end = text.find("/>", at)
    if end < 0:
        fail(what + ": the element around " + key_anchor + " is not self-closing.")
    end += 2
    block = "\n".join((indent + l) if l else l for l in new_element.strip("\n").split("\n"))
    return text[:end] + "\n" + block + text[end:]


def edit_preferences(text):
    if "pref_key_novel_plugins" in text:
        return text
    element = (
        '<androidx.preference.Preference\n'
        '    android:key="@string/pref_key_novel_plugins"\n'
        '    app:iconSpaceReserved="false"\n'
        '    android:title="@string/novel_plugins_settings_title"\n'
        '    android:summary="@string/novel_plugins_settings_summary" />'
    )
    return insert_after_element(text, 'android:key="@string/pref_key_backup_restore"', element, "preferences.xml")


def edit_manifest(text):
    if ".novelscraper.NovelPluginsActivity" in text:
        return text
    element = (
        '<activity\n'
        '    android:name=".novelscraper.NovelPluginsActivity"\n'
        '    android:configChanges="keyboard|keyboardHidden|mcc|mnc|orientation|screenSize|layoutDirection|smallestScreenSize|screenLayout|uiMode|locale"\n'
        '    android:exported="false" />'
    )
    return insert_after_element(text, 'android:name=".backup.BackupActivity"', element, "AndroidManifest.xml")


def edit_backup_sections(text):
    if "NovelPluginsSection" in text:
        return text
    pattern = re.compile(r"^([ \t]*)CookiesSection\(app\),[ \t]*$", re.M)
    if not pattern.search(text):
        fail("BackupSections.kt: 'CookiesSection(app),' was not found.")
    text = pattern.sub(lambda m: m.group(0) + "\n" + m.group(1) + "NovelPluginsSection(app),", text, count=1)
    return add_import(text, "import org.mozilla.fenix.backup.sections.NovelPluginsSection")


def edit_backup_strings(text):
    """The name of the backup section is in novel_scraper_strings.xml now. An earlier version of the novel scraper put
    it in backup_strings.xml: take it out, or the string would be defined twice."""
    return re.sub(r'[ \t]*<string name="backup_section_novel_plugins">[^<]*</string>[ \t]*\n?', "", text)


NEW_APPLICATION_ID = "dev.personal.novelscraper"


def edit_application_id(text):
    """Gives the app its own application id (installed as dev.personal.novelscraper.fenix.debug), so that it is a
    separate app next to the other Fenix builds: no update of them, no clash of version numbers or data."""
    pattern = re.compile(r'^([ \t]*)applicationId[ \t]+"([^"]*)"', re.M)
    match = pattern.search(text)
    if not match:
        fail("app/build.gradle: 'applicationId \"...\"' was not found.")
    if match.group(2) == NEW_APPLICATION_ID:
        return text
    return text[:match.start()] + match.group(1) + '// Novel scraper: its own app, next to the other Fenix builds (was "' + match.group(2) + '").\n' \
        + match.group(1) + 'applicationId "' + NEW_APPLICATION_ID + '"' + text[match.end():]


EDITS = [
    ("mobile/android/fenix/app/build.gradle", edit_application_id),
    (FENIX + "/res/values/backup_strings.xml", edit_backup_strings),
    (JAVA + "/components/Core.kt", edit_core),
    (JAVA + "/settings/SettingsFragment.kt", edit_settings_fragment),
    (FENIX + "/res/xml/preferences.xml", edit_preferences),
    (FENIX + "/AndroidManifest.xml", edit_manifest),
    (JAVA + "/backup/BackupSections.kt", edit_backup_sections),
]


def main():
    if len(sys.argv) != 3:
        fail("usage: apply_overlay.py <novel scraper files> <Fenix fork>")
    overlay = pathlib.Path(sys.argv[1])
    target = pathlib.Path(sys.argv[2])

    in_place = overlay.resolve() == target.resolve()
    for relative in COPY:
        source = overlay / relative
        destination = target / relative
        if not source.exists():
            fail("The novel scraper files have no " + relative + ".")
        if in_place:
            print("in place " + relative)
        elif source.is_dir():
            shutil.copytree(source, destination, dirs_exist_ok=True)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        if not in_place:
            print("copied  " + relative)

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

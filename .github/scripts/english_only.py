#!/usr/bin/env python3
"""Removes the translations from a checkout, so that only English is built (a smaller, faster build and APK).

usage: english_only.py <the folder to clean>

Deletes every resource folder of a language other than English (values-de, values-pt-rBR, values-b+sr+Latn, ...)
below mobile/android. English (values, values-en-*), and the folders that are not about a language (values-night,
values-ldrtl, values-v27, values-sw600dp, ...) stay. Fenix works out the list of its languages from the folders that
are left (getSupportedLocales in app/build.gradle), so the app simply offers English.
"""
import os
import re
import shutil
import sys

LOCALE = re.compile(r"^values-(?:[a-z]{2,3}(?:-r[A-Z]{2,3})?|b\+[A-Za-z0-9+]+)$")
# Looks like a language code but is not one.
NOT_A_LANGUAGE = {"values-car"}


def is_translation(name):
    if not LOCALE.match(name) or name in NOT_A_LANGUAGE:
        return False
    language = name[len("values-"):]
    if language.startswith("b+"):
        language = language[2:]
    return not re.match(r"^en($|[-+])", language)


def main():
    root = os.path.join(sys.argv[1], "mobile", "android")
    removed = 0
    for directory, subdirectories, _ in os.walk(root):
        if os.path.basename(directory) != "res":
            continue
        for name in list(subdirectories):
            if is_translation(name):
                shutil.rmtree(os.path.join(directory, name))
                subdirectories.remove(name)
                removed += 1
    print("Removed " + str(removed) + " translation folders.")


main()

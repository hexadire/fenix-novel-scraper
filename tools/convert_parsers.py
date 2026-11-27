#!/usr/bin/env python3
"""Converts the simple parsers of WebToEpub (js/parsers/*.js) into native plugins (see assets/novel/plugin_guide.md).

Only parsers whose table of contents and chapter text are plain selectors are converted; everything else (APIs, several
pages, custom code) is left out, because the native engine does not run JavaScript.

usage: convert_parsers.py <folder with the parsers> <output .json>
"""
import json, os, re, sys, collections

STR = r'''(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)')'''

def lit(m, i=1):
    text = m.group(i) if m.group(i) is not None else m.group(i + 1)
    # JavaScript string escapes: \" \' and \\ (the selector itself may hold CSS escapes such as \: )
    return re.sub(r'\\(["\'\\])', r'\1', text)

def body_of(src, start):
    i = src.index('{', start); depth = 0
    for j in range(i, len(src)):
        c = src[j]
        if c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: return src[i + 1:j]
    return ''

def method(src, name):
    m = re.search(r'^\s{4}(?:async\s+)?' + name + r'\s*\([^)]*\)\s*\{', src, re.M)
    return body_of(src, m.start()) if m else None

def squash(b):
    b = re.sub(r'//[^\n]*', '', b)
    b = re.sub(r'/\*.*?\*/', '', b, flags=re.S)
    return re.sub(r'\s+', ' ', b).strip()

def selector_ok(s):
    return bool(s) and '${' not in s and len(s) < 300

def convert_toc(body):
    if body is None: return None
    b = squash(body)
    reverse = False
    # Promise.resolve( ... ) wrapper
    b = re.sub(r'return Promise\.resolve\((.*)\);?$', r'return \1;', b)
    if b.endswith('.reverse();') or '.reverse()' in b:
        reverse = '.reverse()' in b
        b = b.replace('.reverse()', '')
    m = re.match(r'^let (\w+) = dom\.querySelector\(' + STR + r'\); return util\.hyperlinksToChapterList\(\1\);?$', b)
    if m: return lit(m, 2) + ' a', reverse
    m = re.match(r'^return util\.hyperlinksToChapterList\(dom\.querySelector\(' + STR + r'\)\);?$', b)
    if m: return lit(m) + ' a', reverse
    m = re.match(r'^return \[\.\.\.dom\.querySelectorAll\(' + STR + r'\)\]\s*\.map\((\w+) => util\.hyperLinkToChapter\(\3\)\);?$', b)
    if m: return lit(m), reverse
    m = re.match(r'^let (\w+) = \[\.\.\.dom\.querySelectorAll\(' + STR + r'\)\]\s*\.map\((\w+) => util\.hyperLinkToChapter\(\4\)\); return \1;?$', b)
    if m: return lit(m, 2), reverse
    return None

def convert_content(body):
    if body is None: return None
    b = squash(body)
    m = re.match(r'^return dom\.querySelector\(' + STR + r'\);?$', b)
    if m: return [lit(m)]
    m = re.match(r'^return dom\.querySelector\(' + STR + r'\) (?:\?\?|\|\|) dom\.querySelector\(' + STR + r'\);?$', b)
    if m: return [lit(m), lit(m, 3)]
    return None

def convert_title(body):
    if body is None: return None
    m = re.match(r'^return dom\.querySelector\(' + STR + r'\);?$', squash(body))
    return lit(m) if m else None

def convert_author(body):
    if body is None: return None
    b = squash(body)
    if re.search(r'replace|split|match|substring|slice|regex', b, re.I): return None
    sels = re.findall(r'querySelector\(' + STR + r'\)', b)
    if len(sels) == 1 and 'textContent' in b:
        return sels[0][0] or sels[0][1]
    return None

def convert_cover(body):
    if body is None: return None
    m = re.match(r'^return util\.getFirstImgSrc\(dom, ' + STR + r'\);?$', squash(body))
    return lit(m) if m else None

def convert_remove(body):
    if body is None: return []
    out = []
    for stmt in re.split(r';', squash(body)):
        stmt = stmt.strip()
        m = re.match(r'^util\.removeChildElementsMatchingSelector\(\w+, ' + STR + r'\)$', stmt)
        if m: out.append(lit(m)); continue
        m = re.match(r'^util\.removeElements\(\w+\.querySelectorAll\(' + STR + r'\)\)$', stmt)
        if m: out.append(lit(m)); continue
        m = re.match(r'^\w+\.querySelectorAll\(' + STR + r'\)\.forEach\(\w+ => \w+\.remove\(\)\)$', stmt)
        if m: out.append(lit(m)); continue
    return out

def convert_file(path):
    src = open(path, encoding='utf-8', errors='ignore').read()
    hosts = [lit(m) for m in re.finditer(r'parserFactory\.register\(\s*' + STR, src)]
    if not hosts or 'registerRule' in src: return None
    cls = re.search(r'class\s+(\w+)\s+extends\s+(\w+)', src)
    if not cls or cls.group(2) != 'Parser': return None
    toc = convert_toc(method(src, 'getChapterUrls'))
    content = convert_content(method(src, 'findContent'))
    # A parser whose list of chapters is not a plain selector is still converted for the text of its chapters: the app finds
    # the list by automatic detection and says so.
    if content is None: return None
    if not all(selector_ok(c) for c in content): return None
    chapters_css, reverse = toc if toc is not None else (None, False)
    if chapters_css is not None and not selector_ok(chapters_css): return None
    plugin = {'id': re.sub(r'[^a-z0-9]+', '-', hosts[0].lower()).strip('-'), 'name': hosts[0], 'hosts': hosts}
    throttle = re.search(r'this\.minimumThrottle\s*=\s*(\d+)', src)
    if throttle: plugin['delayMs'] = int(throttle.group(1))
    info = {}
    t = convert_title(method(src, 'extractTitleImpl'))
    if t and selector_ok(t): info['title'] = t
    a = convert_author(method(src, 'extractAuthor'))
    if a and selector_ok(a): info['author'] = a
    c = convert_cover(method(src, 'findCoverImageUrl'))
    if c and selector_ok(c): info['cover'] = {'css': c + ' img', 'attr': 'src'}
    if info: plugin['info'] = info
    if chapters_css is not None:
        plugin['toc'] = {'chapters': chapters_css, 'order': 'newest-first' if reverse else 'oldest-first'}
    chapter = {'content': content[0] if len(content) == 1 else content}
    ct = convert_title(method(src, 'findChapterTitle'))
    if ct and selector_ok(ct): chapter['title'] = ct
    removal = [r for r in convert_remove(method(src, 'removeUnwantedElementsFromContentElement')) if selector_ok(r)]
    if removal: chapter['remove'] = removal
    plugin['chapter'] = chapter
    return plugin

def main():
    folder, out = sys.argv[1], sys.argv[2]
    plugins = []; seen_hosts = {}
    skipped = collections.Counter()
    for name in sorted(os.listdir(folder)):
        if not name.endswith('.js') or name in ('Template.js', 'DefaultParser.js'): continue
        p = convert_file(os.path.join(folder, name))
        if p is None: skipped['not simple'] += 1; continue
        if any(h in seen_hosts for h in p['hosts']): skipped['duplicate host'] += 1; continue
        for h in p['hosts']: seen_hosts[h] = 1
        plugins.append(p)
    ids = collections.Counter()
    for p in plugins:
        ids[p['id']] += 1
        if ids[p['id']] > 1: p['id'] += '-' + str(ids[p['id']])
    json.dump(plugins, open(out, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
    print(len(plugins), 'plugins converted;', dict(skipped))

main()

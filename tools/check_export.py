#!/usr/bin/env python3
"""Build check (2026-11-22): every preference file and data file the app stores must be classified in
data/Store.kt → ExportExtras (exported with a category, or LOCAL_* = this phone only), so export / import
always covers everything added later. Fails the build when something new isn't classified."""
import os, re, sys
root = os.path.join(os.path.dirname(__file__), '..', 'app/src/main/java/app/vlcinterface')
code = ''
for d, _, fs in os.walk(root):
    for f in fs:
        if f.endswith('.kt'): code += open(os.path.join(d, f)).read()
store = open(os.path.join(root, 'data/Store.kt')).read()
ex = store[store.index('object ExportExtras'):]
def names(block):
    i = ex.index(block); j = ex.index(')', ex.index('(', i))
    return set(re.findall(r'"([^"]+)"', ex[i:j]))
known_prefs = set(re.findall(r'"([^"]+)" to ExportCat', ex[ex.index('val PREFS'):ex.index('val FILES')])) | names('val LOCAL_PREFS')
known_files = set(re.findall(r'"([^"]+)" to ExportCat', ex[ex.index('val FILES'):ex.index('val LOCAL_PREFS')])) | names('val LOCAL_FILES')
prefs = set(re.findall(r'getSharedPreferences\("([^"]+)"', code))
files = {f for f in re.findall(r'File\([\w.]*filesDir, "([^"]+)"', code) if "$" not in f}
bad = sorted(p for p in prefs - known_prefs) + sorted(f for f in files - known_files)
if bad:
    print('Not classified for export / import (add to ExportExtras in data/Store.kt):', ', '.join(bad))
    sys.exit(1)
print(f'export check ok: {len(prefs)} preference files, {len(files)} data files classified')

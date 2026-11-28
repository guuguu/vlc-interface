#!/usr/bin/env python3
"""Builds ui/SettingsIndex.kt: every setting with a literal name, per settings page and section, for the Settings search.
Only names that the page can scroll to are listed (ToggleRow / ValueRow / explain / SettingsLink / ScrollSpeedCard /
SectionTitle and the alarm pages' rows all report their place)."""
import re, os, sys
UI = os.path.join(os.path.dirname(__file__), '..', 'app/src/main/java/app/vlcinterface/ui')
src = {f: open(os.path.join(UI, f)).read() for f in os.listdir(UI) if f.endswith('.kt') and f != 'SettingsIndex.kt'}

def body_of(fname, start_pat):
    s = src[fname]; i = s.find(start_pat)
    if i < 0: sys.exit('missing ' + start_pat)
    j = s.find('{', s.find(')', i)); depth = 0
    for k in range(j, len(s)):
        if s[k] == '{': depth += 1
        elif s[k] == '}':
            depth -= 1
            if depth == 0: return s[j:k]
def branch(name):
    s = src['SettingsScreens.kt']; i = s.find('fun ColumnScope.SettingsGroupBody')
    j = s.find('"%s" -> {' % name, i); j = s.find('{', j); depth = 0
    for k in range(j, len(s)):
        if s[k] == '{': depth += 1
        elif s[k] == '}':
            depth -= 1
            if depth == 0: return s[j:k]

# composable functions defined in ui/, to follow calls into
funcs = {}
for f, s in src.items():
    for m in re.finditer(r'\n(?:private |internal )?fun (?:ColumnScope\.)?([A-Z]\w*)\(', s):
        funcs.setdefault(m.group(1), (f, m.group(0).strip()))
STR = r'"((?:[^"\\$]|\\.)*)"'
ROW = re.compile(r'\b(TitleText|SectionTitle|ToggleRow|ValueRow|HToggleRow|HValueRow|SettingsLink|ScrollSpeedCard|Label|SliderRow|explain)\(\s*' + STR + r'(?:\s*,\s*' + STR + r')?')
SKIP_FUN = {'SectionTitle','ToggleRow','ValueRow','HToggleRow','HValueRow','SettingsLink','ScrollSpeedCard','Label','SliderRow','CardBox',
            'HelperDialog','BackHeader','Divider','Text','Pill','ThinSlider','Field','STextButton','AppSwitch','NowLine','Explain'}

def walk(code, out, sec, seen):
    # A setting written as a plain title line (Text("Start volume", color = C.text …)): it links to its section.
    code = re.sub(r'\bText\((' + STR + r'),\s*color = C\.text\b', r'TitleText(\1, color = C.text', code)
    calls = [(m.start(), 'call', m.group(1)) for m in re.finditer(r'\b([A-Z]\w*)\(', code) if m.group(1) in funcs and m.group(1) not in SKIP_FUN]
    rows = [(m.start(), 'row', m) for m in ROW.finditer(code)]
    for _, kind, x in sorted(calls + rows, key=lambda t: t[0]):
        if kind == 'row':
            k, t, h = x.group(1), x.group(2), x.group(3) or ''
            t = t.replace('\\"', '"'); h = h.replace('\\"', '"')
            if not t.strip(): continue
            if k == 'SectionTitle': sec[0] = t; out.append((t, t, '', True)); continue
            if k == 'TitleText':
                # Dialog titles, questions and sentences aren't settings.
                own = ('anchor("%s")' % x.group(2)) in code
                if (own or sec[0]) and len(t) <= 45 and not t.rstrip().endswith(('?', '.', ':', '…')) \
                        and len(re.sub(r'[^A-Za-z]', '', t)) >= 3 and t not in ('Sample setting', 'Another setting'):
                    out.append((sec[0], t, '', own))     # no own place and no section to open → left out
                continue
            out.append((sec[0], t, h, True))
        elif x not in seen:
            seen.add(x); f, _ = funcs[x]
            walk(body_of(f, _), out, sec, seen)

pages = [  # (id used to open, page name, code)
    ('main', 'Settings', body_of('SettingsScreens.kt', 'fun SettingsScreen(')),
] + [(g, n, branch(g)) for g, n in [('audio','Audio'),('playback','Playback & gestures'),('video','Video'),('radio','Radio'),('look','Look'),
     ('library','Library & files'),('network','Network & NAS'),('vpn','VPN'),('vis','Visualisation'),('logs','Logs'),('assoc','File associations'),('helpers','Helpers')]] + [
    ('alarm', 'Alarm clock', body_of('AlarmScreens.kt', 'fun ColumnScope.AlarmSettingsBody(')),
    ('clock', 'Clock aspect', body_of('AlarmScreens.kt', 'fun ClockAspectScreen(')),
    ('backup', 'Export / import', body_of('SettingsScreens.kt', 'fun BackupScreen(')),
    ('folders', 'Find media folders', body_of('MediaFolders.kt', 'fun MediaFoldersScreen(')),
]
# Parts shown only at times (a search result, a problem, a dialog): never offered, they might not be there to open.
NOT_ALWAYS = {('folders', 'Added'), ('folders', 'Add these?'), ('alarm', 'Alarm health')}
lines = []
for pid, pname, code in pages:
    if pid != 'main': lines.append('    E("%s", "%s", "", "", "", true),' % (pid, pname))   # the page itself
    out = []; walk(code, out, [''], set())
    out = [o for o in out if (pid, o[0]) not in NOT_ALWAYS]
    seen = set()
    for sec, t, h, own in out:
        key = (sec, t)
        if key in seen: continue
        seen.add(key)
        esc = lambda v: v.replace('\\', '\\\\').replace('"', '\\"')
        lines.append('    E("%s", "%s", "%s", "%s", "%s", %s),' % (pid, esc(pname), esc(sec), esc(t), esc(h), 'true' if own else 'false'))
kt = '''package app.vlcinterface.ui

// Generated by tools/gen_settings_index.py — do not edit by hand; run it again after changing settings pages.

/**
 * One setting the Settings search can open: page id, page name, section, setting name, its grey line.
 * [own]: the setting's row reports its own place (the page scrolls to it); otherwise the page opens at its section.
 */
class SettingsEntry(val page: String, val pageName: String, val section: String, val title: String, val hint: String, val own: Boolean)
private fun E(p: String, pn: String, s: String, t: String, h: String, own: Boolean) = SettingsEntry(p, pn, s, t, h, own)

val SETTINGS_INDEX: List<SettingsEntry> = listOf(
%s
)
''' % '\n'.join(lines)
open(os.path.join(UI, 'SettingsIndex.kt'), 'w').write(kt)
print(len(lines), 'entries')

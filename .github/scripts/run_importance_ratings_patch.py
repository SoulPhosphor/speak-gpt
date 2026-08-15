from pathlib import Path
import runpy

patch_path = Path('.github/scripts/apply_importance_ratings_patch.py')
text = patch_path.read_text(encoding='utf-8')

old_strings = '''    <string name="mem_importance_0">0 · Neutral</string>\n    <string name="mem_importance_1">1</string>\n    <string name="mem_importance_2">2</string>\n    <string name="mem_importance_3">3</string>'''
actual_strings = '''    <string name="mem_importance_0">0 · Neutral</string>\n    <string name="mem_importance_1">1 Low importance</string>\n    <string name="mem_importance_2">2 Minor</string>\n    <string name="mem_importance_3">3 Notable</string>'''
if old_strings not in text:
    raise RuntimeError('expected stale importance string anchor not found in patch script')
text = text.replace(old_strings, actual_strings, 1)

old_scan_result = 'return Selection(kept, examined, capBlockedOrdinary && kept.size < normalLimit)'
if old_scan_result not in text:
    raise RuntimeError('expected scan-cap result expression not found in patch script')
text = text.replace(old_scan_result, 'return Selection(kept, examined, capBlockedOrdinary)', 1)

doc_replacements = {
    'rep(doc, "Recommended default: **Off**", "Recommended default: **On**")':
        'rep(doc, "**Recommended Default:** Off.", "**Recommended Default:** On.")',
    'rep(doc, "Values run from `0` to `5`.", "Values are `-2`, `-1`, `0`, `+1`, `+2`, and `+3`.")':
        'rep(doc, "allowed values are 0 through 5;", "allowed values are -2, -1, 0, +1, +2, and +3;")',
    'rep(doc, "New memories start at `0`.", "New memories and memories without an assigned value are treated as `0`.")':
        'rep(doc, "- new memories begin at 0;", "- new memories and memories without an assigned value are treated as 0;")',
    'rep(doc, "Values `1` through `5` are shown as the numbers themselves. Do not add semantic labels such as Low, Medium, or High.", "Values `-2`, `-1`, `+1`, and `+2` are shown as signed numbers. `+3` is shown as `+3 · Always include`. Do not add Low/Medium/High labels.")':
        'rep(doc, "Values 1 through 5 are displayed as numbers. Do not invent semantic labels such as `Critical`, `Minor`, or `Essential` unless the owner later approves them.", "Values `-2`, `-1`, `+1`, and `+2` are shown as signed numbers. `+3` is shown as `+3 · Always include`. Do not add Low/Medium/High labels.")',
}
for stale, current in doc_replacements.items():
    if stale not in text:
        raise RuntimeError(f'expected stale doc patch line not found: {stale[:70]}')
    text = text.replace(stale, current, 1)

patch_path.write_text(text, encoding='utf-8')
runpy.run_path(str(patch_path), run_name='__main__')
Path(__file__).unlink(missing_ok=True)

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

patch_path.write_text(text, encoding='utf-8')
runpy.run_path(str(patch_path), run_name='__main__')
Path(__file__).unlink(missing_ok=True)

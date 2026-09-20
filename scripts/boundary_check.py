#!/usr/bin/env python3
"""Conservative source gate for JSON operations outside Error Prone's type model.

New casts, extraction/coercion, header-map operations and broad catches require
review. The reviewed inventory contains exact statements, occurrence counts and
invariants; moving a statement is harmless, changing/duplicating one is not.
This is a lexical guardrail, not a taint or dominance proof. Runtime mutations
verify the guards surrounding reviewed extraction sites.
"""
import collections
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
RULES = {
    'DecodedCast': r'\(\s*(?:java\.util\.)?(?:Map|List|String|Number|Boolean|Integer|Long)\s*(?:<[^;()]+>)?\s*\)',
    'JsonExtraction': r'\.\s*getAs(?:String|Long|Int|Double|Boolean|JsonObject|JsonArray|BigDecimal)\s*\(|\.\s*fromJson\s*\(',
    'InputLaundering': r'(?:String\.valueOf|Objects\.(?:toString|requireNonNull))\s*\(|(?:Integer|Long|Double)\.parse(?:Int|Long|Double)\s*\(',
    'HeaderMapOperation': r'\b(?:headers|Headers)\.(?:get|containsKey|put|putAll|remove)\s*\(',
    'BroadCatch': r'catch\s*\([^)]*\b(?:RuntimeException|Exception)\b',
    'WireSwitch': r'\bswitch\s*\(',
}


def sites(root=ROOT):
    for path in sorted((root / 'src/main/java').rglob('*.java')):
        original = path.read_text()
        # Preserve line positions while excluding comments, including Javadocs.
        source = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\n]*',
                        lambda m: re.sub(r'[^\n]', ' ', m[0]) if m[0].startswith('/') else m[0],
                        original, flags=re.S)
        optional_names = re.findall(r'\bOptional(?:<[^;]+?>)?\s+(\w+)\s*[=;,)]', source)
        rules = dict(RULES)
        if optional_names:
            names = "|".join(re.escape(name) for name in optional_names)
            rules['OptionalGet'] = rf'\b(?:{names})\.get\s*\('
        for rule, pattern in rules.items():
            for match in re.finditer(pattern, source):
                line = source.count('\n', 0, match.start()) + 1
                statement = original.splitlines()[line - 1].strip()
                yield (str(path.relative_to(root)), rule, statement), line
        for match in re.finditer(r'@SuppressWarnings', source):
            line = source.count('\n', 0, match.start()) + 1
            preceding = original.splitlines()[max(0, line - 3):line - 1]
            if not any('//' in text and 'reason:' in text.lower() for text in preceding):
                yield (str(path.relative_to(root)), 'UnexplainedSuppression', original.splitlines()[line - 1].strip()), line


def main():
    reviewed = json.loads((ROOT / 'scripts/boundary-reviewed.json').read_text())
    allowed = {tuple(item['site']): item['count'] for item in reviewed if item.get('reason')}
    observed = collections.Counter()
    failures = []
    for site, line in sites():
        observed[site] += 1
        if observed[site] > allowed.get(site, 0):
            failures.append(f'{site[0]}:{line}: [{site[1]}] unreviewed boundary operation: {site[2]}')
    for site, count in allowed.items():
        if observed[site] != count and observed[site] < count:
            failures.append(f'{site[0]}: stale boundary review [{site[1]}]: {site[2]}')
    if failures:
        print('\n'.join(failures), file=sys.stderr)
        return 1
    print(f'Boundary source gate passed ({sum(observed.values())} reviewed operations).')
    return 0


if __name__ == '__main__':
    sys.exit(main())

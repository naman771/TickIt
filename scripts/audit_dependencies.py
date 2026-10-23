#!/usr/bin/env python3
"""Query OSV using public Maven coordinates only. No source or credentials are uploaded."""
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.request

root = Path(__file__).resolve().parent.parent
output = root / 'target' / 'dependency-audit.json'
coordinates = root / 'target' / 'runtime-dependencies.txt'
try:
    subprocess.run(['mvn', '-B', 'dependency:list', '-DincludeScope=runtime',
                    '-DoutputFile=' + str(coordinates)], cwd=root, check=True,
                   stdout=subprocess.DEVNULL)
    packages = []
    for line in coordinates.read_text().splitlines():
        match = re.match(r'\s+([^: ]+):([^: ]+):jar:([^: ]+):(?:compile|runtime)', line)
        if match:
            packages.append({'package': {'ecosystem': 'Maven',
                                        'name': match[1] + ':' + match[2]}, 'version': match[3]})
    if not packages:
        raise RuntimeError('No runtime coordinates found')
    request = urllib.request.Request('https://api.osv.dev/v1/querybatch',
                                    data=json.dumps({'queries': packages}).encode(),
                                    headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        results = json.load(response)['results']
    if len(results) != len(packages):
        raise RuntimeError('Incomplete advisory response')
    findings = [dict(package=p['package']['name'], version=p['version'], advisories=r['vulns'])
                for p, r in zip(packages, results) if r.get('vulns')]
    report = {'packagesChecked': len(packages), 'flaggedPackages': len(findings), 'findings': findings}
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    print('Report: ' + str(output))
    sys.exit(1 if findings else 0)
except Exception as error:
    print('Advisory scan incomplete: ' + type(error).__name__, file=sys.stderr)
    sys.exit(2)

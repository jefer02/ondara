"""La etiqueta, versión Android y orden de actualizaciones deben coincidir."""
import re
import sys
from pathlib import Path

properties = dict(line.split('=', 1) for line in Path('version.properties').read_text().splitlines() if '=' in line)
version = properties['versionName']
if not re.fullmatch(r'(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})', version):
    sys.exit('Usa una versión estable X.Y.Z con componentes entre 0 y 999')
major, minor, patch = map(int, version.split('.'))
expected = major * 1_000_000 + minor * 1_000 + patch
if expected <= 0 or int(properties['versionCode']) != expected:
    sys.exit(f'versionCode debe ser {expected} y mayor que cero')
if len(sys.argv) != 2 or sys.argv[1] != f'v{version}':
    sys.exit(f'Publica desde la etiqueta v{version}')
print(f'Versión validada: {version} ({expected})')

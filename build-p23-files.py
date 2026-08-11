import re, sys

log = sys.argv[1] if len(sys.argv) > 1 else 'build-p23-before.log'
files = set()
locs = {}
for line in open(log, encoding='utf-8', errors='replace'):
    if ': error:' not in line:
        continue
    head = line.split(': error:')[0]
    m = re.search(r'([^\\]+\.java):(\d+)', head)
    if m:
        files.add(m.group(1))
        locs.setdefault(m.group(1), 0)
        locs[m.group(1)] += 1

for f in sorted(files):
    print(f'{locs[f]:3d}  {f}')
print('--- total files:', len(files))

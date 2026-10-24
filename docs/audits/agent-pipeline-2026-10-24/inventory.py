"""Read-only inventory of current Agent tool declarations; not a runtime pass claim."""
from pathlib import Path
import re, json, xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
out = Path(__file__).resolve().parent
tools = root / 'pams-backend/src/main/java/com/pams/module/agent/tool'
catalog = (tools.parent / 'registry/AgentCatalog.java').read_text(encoding='utf-8')
tests = list((root / 'pams-backend/src/test/java').rglob('*Test.java'))
test_sources = [(p, p.read_text(encoding='utf-8')) for p in tests]
rows = []
for path in sorted(tools.glob('*.java')):
    s = path.read_text(encoding='utf-8')
    name = re.search(r'String name\(\)\s*\{\s*return "([^"]+)"', s)
    if not name:
        continue
    label = re.search(r'String label\(\)\s*\{\s*return "([^"]+)"', s)
    n = name.group(1)
    rows.append(dict(name=n, label=label.group(1), write='implements WriteAgentTool' in s,
        form='ToolResult.form(' in s, in_catalog=f'"{n}"' in catalog,
        links=re.findall(r'"link",\s*"([^"]+)"', s),
        test_mentions=[str(p.relative_to(root)).replace('\\','/') for p,t in test_sources
            if n in t or path.stem in t]))
declared = set(re.findall(r'"((?:query|search|create|draft|generate|preview|publish|send)[A-Z][A-Za-z]+)"', catalog))
data = dict(total=len(rows), reads=sum(not r['write'] for r in rows),
    writes=sum(r['write'] for r in rows),
    catalog_missing_implementations=sorted(declared-{r['name'] for r in rows}), tools=rows)
(out/'inventory.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in data.items() if k!='tools'},ensure_ascii=False))
for r in rows:
    print(f"{r['name']} | {r['label']} | {'write' if r['write'] else 'read'} | scope={r['in_catalog']} | form={r['form']} | test_mentions={len(r['test_mentions'])}")
xml = next((root/'pams-backend/target/surefire-reports').glob('TEST-*.xml'))
props = ET.parse(xml).getroot().find('properties')
classpath = next(p.attrib['value'] for p in props if p.attrib['name']=='java.class.path')
(out/'classpath.local.txt').write_text(classpath,encoding='utf-8')

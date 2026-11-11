"""Use the actual test runtime classpath, with no alternate transform implementation."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[2]
report = repo / 'pams-backend/target/surefire-reports/TEST-com.pams.module.agent.orchestration.AgentQualityRealModelTest.xml'
properties = {p.get('name'): p.get('value') for p in ET.parse(report).findall('./properties/property')}
classpath = properties.get('surefire.test.class.path', properties['java.class.path'])
out = repo / 'docs/audits/agent-closure-20261103/document-visual'
if not (out / 'poi-original.docx').exists():
    subprocess.run(['java', '--class-path', classpath, str(repo / 'scripts/agent-s5/DocumentRevisionProbe.java'), '--fixture', str(out / 'poi-original.docx'), str(out / 'poi-revised.docx')], check=True, cwd=repo / 'pams-backend')
subprocess.run(['java', '--class-path', classpath, str(repo / 'scripts/agent-s5/InspectDocumentFormats.java'), str(out / 'real-document-format-probe.json'), *map(str, repo.glob('*.docx')), str(out / 'original.docx')], check=True, cwd=repo / 'pams-backend')

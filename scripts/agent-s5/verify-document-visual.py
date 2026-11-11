"""Verify XML/package and actual Word-rendered pages; emit inspectable PNGs."""
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET
import json
import hashlib
import pdfplumber
from pdf2image import convert_from_path, pdfinfo_from_path
from PIL import Image, ImageChops

root = Path(__file__).resolve().parents[2] / 'docs/audits/agent-closure-20261103/document-visual'
before = root / 'poi-original.docx'
after = root / 'poi-revised.docx'
with ZipFile(before) as a, ZipFile(after) as b:
    assert set(a.namelist()) == set(b.namelist())
    changed = [name for name in a.namelist() if a.read(name) != b.read(name)]
    assert changed == ['word/document.xml'], changed
    ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
    old, new = ET.fromstring(a.read('word/document.xml')), ET.fromstring(b.read('word/document.xml'))
    oldtexts, newtexts = old.findall('.//w:t', ns), new.findall('.//w:t', ns)
    changes = [(x.text, y.text) for x, y in zip(oldtexts, newtexts) if x.text != y.text]
    assert changes == [('预算总额为2500元', '预算总额为3000元')], changes
    for node in oldtexts:
        if node.text == '预算总额为2500元': node.text = '预算总额为3000元'
    def semantic(element):
        return (element.tag, sorted(element.attrib.items()), element.text, element.tail, [semantic(child) for child in element])
    assert semantic(old) == semantic(new), 'Unexpected XML/format change'

poppler = Path.home() / '.cache/codex-runtimes/codex-primary-runtime/dependencies/native/poppler/Library/bin'
images = []
for name in ('poi-original', 'poi-revised'):
    pdf = root / f'{name}.pdf'
    assert pdfinfo_from_path(pdf, poppler_path=str(poppler))['Pages'] == 1
    image = convert_from_path(pdf, dpi=144, poppler_path=str(poppler))[0].convert('RGB')
    image.save(root / f'{name}-page-1.png')
    images.append(image)
diff = ImageChops.difference(*images)
bbox = diff.getbbox()
assert bbox is not None
with pdfplumber.open(root / 'poi-original.pdf') as pdf:
    rects = [word for word in pdf.pages[0].extract_words() if '2500' in word['text']]
assert len(rects) == 1, rects
rect = rects[0]
allowed = (int(rect['x0']*2)-8, int(rect['top']*2)-8, int(rect['x1']*2)+8, int(rect['bottom']*2)+8)
assert bbox[0] >= allowed[0] and bbox[1] >= allowed[1] and bbox[2] <= allowed[2] and bbox[3] <= allowed[3], (bbox, allowed)
from pypdf import PdfReader
creator = str(PdfReader(root / 'poi-original.pdf').metadata.get('/Creator', 'UNKNOWN'))
record = {'status': 'PASS', 'renderer': creator, 'renderTransport': 'Word.Application COM ExportAsFixedFormat', 'microsoftWordVerified': False, 'pages': 1,
          'changedPackageParts': changed, 'textChanges': changes, 'changedPixelsBoundingBox': bbox,
          'unchangedPixelsOutsideBudgetDigits': True, 'sourceSHA256': hashlib.sha256(before.read_bytes()).hexdigest(),
          'revisedSHA256': hashlib.sha256(after.read_bytes()).hexdigest(),
          'scope': 'SYNTHETIC_SUPPORTED_PARAGRAPH_DOCX_NOT_GENERAL_DOCUMENT_SUPPORT', 'visualInspection': 'PENDING'}
(root / 'verification.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(record, ensure_ascii=False))

"""Synthetic supported-format fixture; generated with the bundled document runtime."""
import sys
from pathlib import Path
from docx import Document
from docx.shared import Pt, Cm
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

output = Path(sys.argv[1]).resolve()
output.mkdir(parents=True, exist_ok=True)
doc = Document()
section = doc.sections[0]
section.page_width, section.page_height = Cm(21), Cm(29.7)
section.top_margin = section.bottom_margin = Cm(2.5)
section.left_margin = section.right_margin = Cm(2.5)
style = doc.styles['Normal']
style.font.name, style.font.size = '宋体', Pt(12)
style.element.rPr.rFonts.set(qn('w:eastAsia'), '宋体')
for text, bold in [
    ('校园文化交流活动策划书', True),
    ('活动日期为2026年12月1日，地点为校内礼堂。', False),
    ('预算总额为2500元', True),
    ('活动流程包括签到、交流和总结三个环节。', False),
    ('负责人、日期、地点与流程均须保留，不因预算修订而变更。', False),
    ('本文件为隔离验收样本，不用于实际活动审批。', False),
]:
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(12)
    r = p.add_run(text)
    r.bold = bold
    r.font.name = '宋体'
    r._element.get_or_add_rPr().rFonts.set(qn('w:eastAsia'), '宋体')
    if text.endswith('策划书'):
        r.font.size = Pt(18)
        p.alignment = 1
doc.save(output / 'original.docx')

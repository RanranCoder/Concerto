/** 导出议程表：docx 编号列表（标题「活动议程表」居中 + 每步编号项，docx 库动态 import 分包） */
export async function agendaToDocx(
  agendas: Array<{ stepNo: number; title: string; remark?: string | null }>,
): Promise<Blob> {
  const { Document, Packer, Paragraph, TextRun, AlignmentType } = await import('docx')
  const children = [
    new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { after: 200 },
      children: [new TextRun({ text: '活动议程表', font: '宋体', bold: true, size: 28 })],
    }),
    ...agendas.map(
      (a) =>
        new Paragraph({
          spacing: { after: 60 },
          children: [new TextRun({ text: `${a.stepNo}. ${a.title}${a.remark ? '　' + a.remark : ''}`, font: '宋体', size: 24 })],
        }),
    ),
  ]
  const doc = new Document({ sections: [{ children }] })
  return Packer.toBlob(doc)
}

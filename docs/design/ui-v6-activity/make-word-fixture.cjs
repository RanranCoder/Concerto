// Reproducible DOCX fixture for document-layout QA; generated with the project's installed library.
const fs=require('node:fs'),path=require('node:path');
const {Document,Packer,Paragraph,TextRun,Table,TableRow,TableCell,Header,Footer,PageNumber,PageBreak,HeadingLevel,AlignmentType,WidthType,ImageRun}=require('../../../pams-web/node_modules/docx');
const p=(text,options={})=>new Paragraph({children:[new TextRun({text,font:'SimSun',size:24})],spacing:{after:160,line:360},...options});
const heading=text=>p(text,{heading:HeadingLevel.HEADING_1});
const row=values=>new TableRow({children:values.map(text=>new TableCell({children:[p(text)]}))});
const doc=new Document({styles:{default:{document:{run:{font:'SimSun',size:24}}}},sections:[{properties:{page:{size:{width:11906,height:16838},margin:{top:1440,bottom:1440,left:1440,right:1440}}},headers:{default:new Header({children:[p('协奏 · 活动策划书格式测试',{alignment:AlignmentType.RIGHT})]})},footers:{default:new Footer({children:[new Paragraph({alignment:AlignmentType.CENTER,children:[new TextRun('第 '),new TextRun({children:[PageNumber.CURRENT]}),new TextRun(' 页')]})]})},children:[
  new Paragraph({alignment:AlignmentType.CENTER,children:[new ImageRun({data:fs.readFileSync(path.resolve(__dirname,'../../../pams-web/public/favicon.png')),type:'png',transformation:{width:42,height:42}})]}),
  p('活动策划书 · Word 格式试阅',{heading:HeadingLevel.TITLE,alignment:AlignmentType.CENTER}),
  heading('一、活动背景'),p('本文件用于验证 Word 文档的标题、正文、表格、图片、页眉页脚与分页显示。'),
  heading('二、活动安排'),new Table({width:{size:100,type:WidthType.PERCENTAGE},rows:[row(['时间','活动环节','负责人']),row(['19:00','集中学习','张老师']),row(['19:50','小组讨论','林同学']),row(['20:15','总结交流','周同学'])]}),
  p('格式核对：这是一段加粗与彩色文字。',{children:[new TextRun({text:'格式核对：',bold:true}),new TextRun({text:'此处保留 Word 原稿的字色与字号。',color:'1668DC',size:28})]}),
  new Paragraph({children:[new PageBreak()]}),
  heading('三、执行与保障'),p('工作人员提前到场，检查音响与投影，核对签到名单。'.repeat(180)),
  heading('四、经费预算'),p('资料印制 120 元，场地布置 80 元，合计 200 元。'),
  p('文末校验：全部内容显示完成。')
]}]});
const output=path.join(__dirname,'fixtures');fs.mkdirSync(output,{recursive:true});
Packer.toBuffer(doc).then(buffer=>{fs.writeFileSync(path.join(output,'a4-plan.docx'),buffer);console.log('DOCX fixture created');});

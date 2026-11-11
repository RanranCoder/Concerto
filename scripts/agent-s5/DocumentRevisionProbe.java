import com.pams.module.agent.service.BudgetDocxRevision;
import com.pams.module.agent.service.PlanDocumentReader;
import java.nio.file.*;
/** Runs the production transform on explicit copies. Never rewrites the source. */
class DocumentRevisionProbe {
    public static void main(String[] args)throws Exception{
        if(args.length==3&&args[0].equals("--fixture")){
            try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()){
                for(String text:java.util.List.of("校园文化交流活动策划书","活动日期为2026年12月1日，地点为校内礼堂。","预算总额为2500元","活动流程包括签到、交流和总结三个环节。","负责人、日期、地点与流程均须保留。","本文件为隔离验收样本，不用于实际活动审批。")){
                    var p=doc.createParagraph();p.setSpacingAfter(240);var r=p.createRun();r.setFontFamily("宋体");r.setFontSize(text.endsWith("策划书")?18:12);r.setBold(text.endsWith("策划书")||text.startsWith("预算"));r.setText(text);
                }
                doc.write(out);Files.write(Path.of(args[1]),out.toByteArray(),StandardOpenOption.CREATE_NEW);
            }
            args=new String[]{args[1],args[2]};
        }
        if(args.length!=2)throw new IllegalArgumentException("source output");
        var source=Path.of(args[0]).toAbsolutePath().normalize();var target=Path.of(args[1]).toAbsolutePath().normalize();
        if(source.equals(target))throw new IllegalArgumentException("Source must remain immutable");
        byte[] original=Files.readAllBytes(source);
        byte[] revised=BudgetDocxRevision.replace(original,"预算总额为2500元","预算总额为3000元");
        Files.write(target,revised,StandardOpenOption.CREATE_NEW);
        System.out.println("sourceSha256="+PlanDocumentReader.hash(original)+" revisedSha256="+PlanDocumentReader.hash(revised));
    }
}

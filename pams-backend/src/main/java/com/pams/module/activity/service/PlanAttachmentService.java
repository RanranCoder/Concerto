package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.archive.repository.FileRecordRepository;
import com.pams.module.archive.service.FileService;
import com.pams.module.archive.service.FileStorageService;
import com.pams.module.activity.entity.ActivityPlan;
import org.apache.poi.xwpf.usermodel.*;
import java.nio.file.Files;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** Check references before applying changes: a guessed private file ID must not grant access. */
@Service
public class PlanAttachmentService {
    private final FileService files;
    private final FileRecordRepository records;
    private final FileStorageService storage;
    private static final Pattern FILE=Pattern.compile("/api/files/(\\d+)/");
    private static final Pattern LEGACY=Pattern.compile("/uploads/([^\\s\\\"'<>?#]+)");
    public PlanAttachmentService(FileService files,FileRecordRepository records,FileStorageService storage) {
        this.files=files; this.records=records; this.storage=storage;
    }
    /** Derive search text from the actual DOCX bytes, never trust a client supplied index. */
    public void indexDocument(ActivityPlan plan,Long fileId,ToolContext ctx) {
        var rec=records.findById(fileId).orElseThrow(() -> new BizException(4001,"文件不存在"));
        // Plan authors may save their own PLAN upload without unrelated archive permissions.
        if(ctx==null || ctx.getLoginUser()==null || !Objects.equals(rec.getOrgId(),ctx.getLoginUser().getOrgId()))
            throw new BizException(4001,"文件不存在");
        if(!Objects.equals(rec.getUploaderId(),ctx.getUserId())) files.getReadableRecord(fileId,ctx);
        if(rec.getSize()!=null && rec.getSize()>20L*1024*1024)
            throw new BizException(4001,"DOCX 文件应小于 20MB");
        if(!"PLAN".equalsIgnoreCase(rec.getBizType()) || !rec.getFilename().toLowerCase(Locale.ROOT).endsWith(".docx"))
            throw new BizException(4001,"请上传有效的 DOCX 策划书");
        try(var in=Files.newInputStream(storage.physicalPath(rec.getPath())); var doc=new XWPFDocument(in)) {
            StringBuilder text=new StringBuilder();
            for(var element:doc.getBodyElements()) {
                String line=element instanceof XWPFParagraph p?p.getText():element instanceof XWPFTable t?t.getText():"";
                if(text.length()+line.length()>2_000_000) throw new BizException(4001,"文档正文过长");
                text.append(line).append('\n');
            }
            plan.setDocumentFileId(fileId);
            plan.setDocumentText(text.toString().strip());
            // These are indexes only. Native DOCX is authoritative and must never be rebuilt from them.
            plan.setBackground(null); plan.setPurpose(null); plan.setFlow(null); plan.setNotice(null);
            plan.setEmergency(null); plan.setBudget(null); plan.setSectionOrder(null);
            String index=plan.getDocumentText();
            plan.setContent(org.jsoup.nodes.Entities.escape(index.substring(0,Math.min(index.length(),8000))).replace("\n","<br>"));
        } catch(BizException e) { throw e; }
        catch(Exception e) { throw new BizException(4001,"无法读取 DOCX，请确认文件有效且未加密"); }
    }
    /** Called only after PlanService.getEntity has enforced the version's read policy. */
    public org.springframework.core.io.Resource documentResource(ActivityPlan plan) {
        if(plan.getDocumentFileId()==null) throw new BizException(4001,"此版本尚无原生文档");
        var rec=records.findById(plan.getDocumentFileId()).orElseThrow(() -> new BizException(4001,"文件不存在"));
        if(!Objects.equals(rec.getOrgId(),plan.getOrgId())) throw new BizException(4001,"文件不存在");
        var path=storage.physicalPath(rec.getPath());
        if(!Files.isRegularFile(path)) throw new BizException(4001,"文件不存在");
        return new org.springframework.core.io.FileSystemResource(path);
    }
    public void validate(PlanRequest request,ToolContext ctx) {
        var ids=new HashSet<Long>();
        for(String value:Arrays.asList(request.getBackground(),request.getPurpose(),request.getContent(),request.getFlow(),
                request.getNotice(),request.getEmergency(),request.getBudget(),request.getSectionOrder())) {
            if(value==null) continue;
            String decoded=org.jsoup.parser.Parser.unescapeEntities(value.replace("\\/","/"),false);
            var matcher=FILE.matcher(decoded);
            while(matcher.find()) {
                try { ids.add(Long.parseLong(matcher.group(1))); }
                catch(NumberFormatException e) { throw new BizException(4001,"无效的文档图片引用"); }
            }
            matcher=LEGACY.matcher(decoded);
            while(matcher.find()) ids.add(records.findFirstByPath(matcher.group(1))
                    .orElseThrow(() -> new BizException(4001,"文件不存在")).getId());
        }
        for(Long id:ids) files.getReadableRecord(id,ctx);
    }
}

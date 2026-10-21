package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.archive.repository.FileRecordRepository;
import com.pams.module.archive.service.FileService;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** Check references before applying changes: a guessed private file ID must not grant access. */
@Service
public class PlanAttachmentService {
    private final FileService files;
    private final FileRecordRepository records;
    private static final Pattern FILE=Pattern.compile("/api/files/(\\d+)/");
    private static final Pattern LEGACY=Pattern.compile("/uploads/([^\\s\\\"'<>?#]+)");
    public PlanAttachmentService(FileService files,FileRecordRepository records) { this.files=files; this.records=records; }
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

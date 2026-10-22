package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.service.PlanAttachmentService;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.archive.entity.FileRecord;
import com.pams.module.archive.repository.FileRecordRepository;
import com.pams.module.archive.service.FileService;
import com.pams.module.archive.service.FileStorageService;
import com.pams.security.LoginUser;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanDocumentAccessTest {
    @TempDir Path directory;
    FileService files=mock(FileService.class);
    FileRecordRepository records=mock(FileRecordRepository.class);
    FileRecord upload=new FileRecord();
    ToolContext author=new ToolContext(new LoginUser(10L,"author","作者","CUSTOM",1,null,null,1L),
        List.of(new SimpleGrantedAuthority("activity:view"),new SimpleGrantedAuthority("activity:edit")),null,null);
    PlanAttachmentService attachments;
    @BeforeEach void setup() throws Exception {
        try(var doc=new XWPFDocument(); var out=Files.newOutputStream(directory.resolve("native.docx"))) {
            doc.createParagraph().createRun().setText("无材料权限也可保存自己的文档"); doc.write(out);
        }
        upload.setId(50L); upload.setOrgId(1L); upload.setUploaderId(10L); upload.setBizType("PLAN");
        upload.setFilename("策划书.docx"); upload.setPath("native.docx");
        when(records.findById(50L)).thenReturn(Optional.of(upload));
        attachments=new PlanAttachmentService(files,records,new FileStorageService(directory));
    }
    @Test void activityAuthorCanBindOwnUploadWithoutArchivePermissions() throws Exception {
        var plan=new ActivityPlan(); plan.setOrgId(1L);
        attachments.indexDocument(plan,50L,author);
        assertThat(plan.getDocumentText()).contains("无材料权限");
        assertThat(attachments.documentResource(plan).contentLength()).isPositive();
        verifyNoInteractions(files);
    }
    @Test void tenantMismatchAndUnreadableForeignUploadCannotBeBound() {
        var plan=new ActivityPlan(); plan.setOrgId(1L);
        upload.setOrgId(2L);
        assertThatThrownBy(() -> attachments.indexDocument(plan,50L,author)).isInstanceOf(BizException.class);
        upload.setOrgId(1L); upload.setUploaderId(20L);
        when(files.getReadableRecord(50L,author)).thenThrow(new BizException(4001,"文件不存在"));
        assertThatThrownBy(() -> attachments.indexDocument(plan,50L,author)).isInstanceOf(BizException.class);
        assertThat(plan.getDocumentFileId()).isNull();
    }
}

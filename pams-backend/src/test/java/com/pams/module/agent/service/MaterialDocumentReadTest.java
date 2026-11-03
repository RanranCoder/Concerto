package com.pams.module.agent.service;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.archive.entity.*;
import com.pams.module.archive.repository.MaterialRepository;
import com.pams.module.archive.service.*;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MaterialDocumentReadTest {
    @TempDir Path root;final MaterialRepository materials=mock(MaterialRepository.class);final FileService files=mock(FileService.class);
    final ToolContext ctx=new ToolContext(new LoginUser(1L,"u","u","TEACHER",5,null,null,1L),List.of("material:view","file:view").stream().map(SimpleGrantedAuthority::new).toList(),null,null);
    AgentMaterialReadService service;FileRecord file;
    @BeforeEach void setup()throws Exception{OrgContext.set(1L);service=new AgentMaterialReadService(materials,files,new FileStorageService(root),new AgentPreparedOperations(null,null));
        var m=new Material();m.setId(2L);m.setFileId(3L);m.setOrgId(1L);when(materials.findById(2L)).thenReturn(Optional.of(m));
        file=new FileRecord();file.setId(3L);file.setOrgId(1L);file.setFilename("正文.txt");file.setPath("body.txt");file.setBizType("OTHER");when(files.getReadableRecord(3L,ctx)).thenReturn(file);Files.writeString(root.resolve("body.txt"),"原文".repeat(1500));}
    @AfterEach void clear(){OrgContext.clear();}
    @Test void nativeFileAuthorizationAndHashAreCheckedForEveryPage()throws Exception{
        var first=service.read(ctx,Map.of("materialId",2));assertThat(first.payload()).containsEntry("hasMore",true).containsEntry("complete",false);String baseline=first.payload().get("baseline").toString();
        var second=service.read(ctx,Map.of("materialId",2,"cursor",4,"baseline",baseline));assertThat(second.payload()).containsEntry("hasMore",false);verify(files,times(2)).getReadableRecord(3L,ctx);
        Files.writeString(root.resolve("body.txt"),"并发改动");assertThatThrownBy(()->service.read(ctx,Map.of("materialId",2,"cursor",4,"baseline",baseline))).hasMessageContaining("已变化");
    }
    @Test void fileDenialTraversalSensitiveKindsAndUnsupportedFormatNeverReturnText(){
        file.setPath("../outside.txt");assertThatThrownBy(()->service.read(ctx,Map.of("materialId",2))).hasMessageContaining("非法");
        file.setBizType("ROSTER");assertThatThrownBy(()->service.read(ctx,Map.of("materialId",2))).hasMessageContaining("开放范围");
        file.setBizType("OTHER");file.setFilename("扫描件.pdf");assertThatThrownBy(()->service.read(ctx,Map.of("materialId",2))).hasMessageContaining("仅支持");
        when(files.getReadableRecord(3L,ctx)).thenThrow(new com.pams.common.BizException(404,"文件不存在"));assertThatThrownBy(()->service.read(ctx,Map.of("materialId",2))).hasMessageContaining("文件不存在");
    }
    @Test void supportedDocxBodyReachesOnlyTheMaskedBoundedTextChannel()throws Exception{
        file.setFilename("正文.docx");try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=Files.newOutputStream(root.resolve("body.txt"))){document.createParagraph().createRun().setText("联系13812345678核对原文");document.write(out);}
        var tool=new com.pams.module.agent.tool.ReadMaterialDocumentTool(service);var result=tool.execute(ctx,Map.of("materialId",2));assertThat(result.payload()).containsEntry("complete",true);
        String text=new PolicyGuard().egressText(tool.sensitivity(),tool.toLlmText(ctx,result));assertThat(text).contains("138****5678","核对原文").doesNotContain("13812345678");
    }
    @Test void compressedDocxExpansionIsBoundedBeforeParsing()throws Exception{
        file.setFilename("压缩正文.docx");try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(root.resolve("body.txt")))){zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));zip.write(new byte[4*1024*1024+1]);zip.closeEntry();}
        assertThatThrownBy(()->service.read(ctx,Map.of("materialId",2))).hasMessageContaining("预算");
    }
}

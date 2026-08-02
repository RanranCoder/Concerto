package com.pams.module.archive;

import com.pams.module.archive.entity.FileRecord;
import com.pams.module.archive.repository.FileRecordRepository;
import com.pams.module.archive.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileStorageServiceTest {

    @Test
    void sanitize_rejectsDangerousName() {
        FileStorageService svc = new FileStorageService(java.nio.file.Path.of("target/uploads"));
        String safe = svc.sanitize("../../evil/名单.xlsx");
        assertThat(safe).doesNotContain("..").doesNotContain("/");
    }

    @Test
    void detectBizType_fromExtension() {
        FileStorageService svc = new FileStorageService(java.nio.file.Path.of("target/uploads"));
        assertThat(svc.bizTypeOf("策划书.docx")).isEqualTo("PLAN");
        assertThat(svc.bizTypeOf("签到表.xlsx")).isEqualTo("SIGNIN");
        assertThat(svc.bizTypeOf("照片.jpg")).isEqualTo("PHOTO");
    }

    @Test
    void sanitize_dropsOfficeTempPrefix() {
        FileStorageService svc = new FileStorageService(java.nio.file.Path.of("target/uploads"));
        assertThat(svc.sanitize("~$名单.xlsx")).isEqualTo("名单.xlsx");
    }

    @Test
    void sanitize_keepsDangerousNameNullFree() {
        FileStorageService svc = new FileStorageService(java.nio.file.Path.of("target/uploads"));
        // Windows 文件系统不允许 NUL 字节（字符串程序化构造，避免源码含裸控制字节）
        String dirty = "bad" + (char) 0 + "name.docx";
        assertThat(svc.sanitize(dirty)).doesNotContain("" + (char) 0);
    }

    @Test
    void sanitize_rejectsControlCharactersBeforePathParsing() {
        FileStorageService svc = new FileStorageService(java.nio.file.Path.of("target/uploads"));
        // 控制字符先于 Path.of 被剥离，Windows 上不会抛 InvalidPathException
        assertThat(svc.sanitize("ok" + (char) 10 + "name.docx")).isEqualTo("okname.docx");
    }

    @Test
    void bizType_detectsScheduleAndAttendanceAndInvoice() {
        FileStorageService svc = new FileStorageService(java.nio.file.Path.of("target/uploads"));
        assertThat(svc.bizTypeOf("排班表.xlsx")).isEqualTo("SCHEDULE");
        assertThat(svc.bizTypeOf("值班安排.docx")).isEqualTo("SCHEDULE");
        assertThat(svc.bizTypeOf("考勤汇总.xlsx")).isEqualTo("ATTENDANCE");
        assertThat(svc.bizTypeOf("发票.pdf")).isEqualTo("INVOICE");
        assertThat(svc.bizTypeOf("新闻稿.docx")).isEqualTo("NEWS");
        assertThat(svc.bizTypeOf("推文稿.docx")).isEqualTo("ARTICLE");
        assertThat(svc.bizTypeOf("演示.pptx")).isEqualTo("PPT");
        assertThat(svc.bizTypeOf("随手拍.jpg")).isEqualTo("PHOTO");
        assertThat(svc.bizTypeOf("其他.png")).isEqualTo("PHOTO");
        assertThat(svc.bizTypeOf("notes.txt")).isEqualTo("OTHER");
    }

    @Test
    void store_writesFileAndPersistsRecord() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "名单.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "hello".getBytes());
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("pams-upload");
        FileRecordRepository repo = mock(FileRecordRepository.class);
        when(repo.save(org.mockito.ArgumentMatchers.any(FileRecord.class)))
                .thenAnswer(inv -> { FileRecord r = inv.getArgument(0); r.setId(1L); return r; });
        FileStorageService svc = new FileStorageService(root.toString(), repo);

        FileRecord rec = svc.store(file, null, 1L);

        assertThat(rec.getFilename()).isEqualTo("名单.xlsx");
        assertThat(rec.getPath()).startsWith("20");
        assertThat(rec.getSize()).isEqualTo(5L);
        assertThat(rec.getBizType()).isEqualTo("OTHER");
        assertThat(root.resolve(rec.getPath())).exists();
        verify(repo).save(org.mockito.ArgumentMatchers.any(FileRecord.class));
    }

    @Test
    void store_usesExplicitBizType() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "签到表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "data".getBytes());
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("pams-upload");
        FileRecordRepository repo = mock(FileRecordRepository.class);
        when(repo.save(org.mockito.ArgumentMatchers.any(FileRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        FileStorageService svc = new FileStorageService(root.toString(), repo);

        FileRecord rec = svc.store(file, "SIGNIN", 1L);

        assertThat(rec.getBizType()).isEqualTo("SIGNIN");
        assertThat(rec.getPath()).startsWith("20");
    }
}

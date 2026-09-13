package com.pams.common;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Excel 上传解析守卫（PRD-v5 X1：Excel 解析健壮性）。
 *
 * 1) 魔数校验：xlsx 必须为 ZIP（PK..）、xls 必须为 OLE2（D0 CF 11 E0）。
 *    伪装成 Excel 的畸形/恶意文件在进入 POI 前直接拒绝，避免不可控异常或 OOM；
 * 2) 行数上限：单表超过 {@link #MAX_ROWS} 行拒绝——名单/课表类导入不存在上万行的合法场景；
 * 3) 体积上限：调用方对 MultipartFile 先做 {@link #checkSize}。
 *
 * 所有用户上传的 xlsx/xls 解析统一经由 {@link #open} 创建 Workbook，不要直接调 WorkbookFactory.create。
 */
public final class ExcelGuard {

    /** 名单类导入的合理上限：一届名单/一个部门课表远小于此，超出按恶意文件处理 */
    public static final int MAX_ROWS = 10_000;

    /** 单个 Excel 上传体积上限 */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private ExcelGuard() {
    }

    /** MultipartFile 体积守卫（配合 multipart 全局上限，防大文件进解析管线） */
    public static void checkSize(Long size) {
        if (size != null && size > MAX_BYTES) {
            throw new BizException(4001, "Excel 文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限");
        }
    }

    /**
     * 魔数 + 行数守卫后创建 Workbook。调用方自行 try-with-resources 关闭。
     * 抛出的 BizException 会原样穿透调用方的 IOException catch。
     */
    public static Workbook open(InputStream in) {
        if (in == null) {
            throw new BizException(4001, "上传文件为空");
        }
        try {
            BufferedInputStream bin = new BufferedInputStream(in);
            bin.mark(4);
            byte[] head = new byte[4];
            int n = bin.read(head);
            bin.reset();
            if (n < 4 || !(isZip(head) || isOle2(head))) {
                throw new BizException(4001, "不是有效的 Excel 文件（仅支持 xlsx/xls）");
            }
            Workbook wb = WorkbookFactory.create(bin);
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (wb.getSheetAt(i).getLastRowNum() > MAX_ROWS) {
                    wb.close();
                    throw new BizException(4001, "Excel 单表超过 " + MAX_ROWS + " 行上限，请拆分后上传");
                }
            }
            return wb;
        } catch (IOException e) {
            throw new BizException(4001, "Excel 文件解析失败");
        }
    }

    private static boolean isZip(byte[] h) {
        return (h[0] & 0xFF) == 'P' && (h[1] & 0xFF) == 'K';
    }

    private static boolean isOle2(byte[] h) {
        return (h[0] & 0xFF) == 0xD0 && (h[1] & 0xFF) == 0xCF && (h[2] & 0xFF) == 0x11 && (h[3] & 0xFF) == 0xE0;
    }
}

# 无课表生成（批量导入课表 → 自动生成无课表）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现批量导入班级课表 Excel → 自动生成每人的无课表（xlsx + Markdown 双产物），覆盖后端独立算法模块、批量上传接口、前端批量导入入口。

**Architecture:** 后端在 `com.pams.module.schedule.generator` 新增纯 Java 算法层（解析班级课表 → 计算无课周 → 格式化），不依赖 Spring/DB、可独立单测；Spring 集成层 `NoClassScheduleImportService` + `CourseScheduleController` 暴露 `POST /api/course-schedules/import` 与下载接口，生成结果写入统一输出目录；前端 `CourseSchedule.tsx` 新增「批量导入」Tab。

**Tech Stack:** Java 21 · Spring Boot 4.0.2 · Apache POI 5.4.1（pom 已有）· JUnit 5 / AssertJ / Mockito · React 18 + TypeScript + antd 5 + Vite

## Global Constraints

- **命名**：统一用「无课表」/ `NoClassSchedule`，禁用「干部无课表」/ `CadreFreeSchedule`。
- **无地点后缀**：无课表只表达空闲周次，输出不加「（X教）」等地点信息。
- **输出维度**：周一~周五 × 6 行节次（第一二节…第十一十二节），周六日不输出。
- **全集**：默认 1~18 周，`maxWeek` 参数可配置（<1 或 >30 时回 18）。
- **姓名来源**：从文件名自动解析（`姓名-文件-…`、`姓名_…`、`姓名-…`、前导中文 4 种模式），解析失败记入 `failed` 清单，不阻塞其他文件。
- **BizException 码**：schedule 模块用 27xx，新接口用 **2702~2705**（2701 已被「请选择学期」占用）。
- **不新增后端依赖**（POI 5.4.1 已有）；**v1 不落库**（不写 `course_schedule`/`free_schedule` 表）。
- 前端文案用中文；「批量导入」Tab 仅部长及以上（`roleLevel >= 3`）可见。
- 后端验证：`cd pams-backend && mvn -q test`；前端验证：`cd pams-web && npm run build`（tsc + vite）。
- 真实课表测试数据不进入 git，测试用例在测试代码内用 `XSSFWorkbook` 程序化构造（沿用 `RosterImportServiceTest.buildXlsx` 模式）。

---

## 文件结构

**后端新增**（`pams-backend/src/main/java/com/pams/module/schedule/`）：
- `generator/Course.java` — record：课程名 + 有课周集合
- `generator/SlotKey.java` — record：(星期1-7, 节次1-6)
- `generator/PersonTimetable.java` — record：姓名 + 课程表
- `generator/NoClassScheduleCell.java` / `generator/NoClassScheduleRow.java` — 无课表网格记录
- `generator/WeekRangeParser.java` — 周次字符串 → 有课周集合
- `generator/FreeWeekFormatter.java` — 无课周集合 → 展示串
- `generator/FreeWeekCalculator.java` — 补集计算
- `generator/TimetableNameExtractor.java` — 文件名 → 姓名
- `generator/ClassTimetableParser.java` — POI 读班级课表 → (星期,节次)→课程列表
- `generator/NoClassScheduleGenerator.java` — 批量编排 → 网格
- `generator/NoClassScheduleExcelWriter.java` — 写 xlsx
- `generator/NoClassScheduleMarkdownWriter.java` — 写 Markdown
- `dto/NoClassScheduleCellVO.java`、`dto/NoClassScheduleRowVO.java`、`dto/ImportFileFailureVO.java`、`dto/NoClassScheduleImportVO.java`
- `service/NoClassScheduleImportService.java` — Spring 集成

**后端修改**：
- `controller/CourseScheduleController.java` — 构造器加依赖 + 2 个端点

**后端测试新增**（`pams-backend/src/test/java/com/pams/module/schedule/`）：
- `ClassTimetableParserTest.java`（含 `buildTimetable()` 共享 fixture）
- `WeekRangeParserTest.java`、`FreeWeekFormatterTest.java`、`FreeWeekCalculatorTest.java`、`TimetableNameExtractorTest.java`
- `NoClassScheduleGeneratorTest.java`、`NoClassScheduleExcelWriterTest.java`、`NoClassScheduleMarkdownWriterTest.java`
- `NoClassScheduleImportServiceTest.java`、`CourseScheduleControllerTest.java`

**前端修改**：
- `pams-web/src/api/courseSchedule.ts` — 加 import/download API
- `pams-web/src/pages/routine/CourseSchedule.tsx` — 加「批量导入」Tab

---

## Task 1: 模型 record 与 `WeekRangeParser`

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/Course.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/SlotKey.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/PersonTimetable.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/NoClassScheduleCell.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/NoClassScheduleRow.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/WeekRangeParser.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/WeekRangeParserTest.java`

**Interfaces:**
- Produces: `WeekRangeParser.parse(String weekText, int maxWeek) → Set<Integer>`（`;`/`,` 分隔多段，`单`/`双` 过滤，按 maxWeek 截断）

- [ ] **Step 1: 写 5 个模型 record**

```java
package com.pams.module.schedule.generator;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record Course(String name, Set<Integer> weeks) {}

public record SlotKey(int dayOfWeek, int period) {}

public record PersonTimetable(String name, Map<SlotKey, List<Course>> timetable) {}

public record NoClassScheduleCell(String name, String freeWeeks) {}

public record NoClassScheduleRow(int period, String label, String halfDay, Map<Integer, List<NoClassScheduleCell>> cells) {}
```

（5 个顶层 public record 需分文件，各文件仅含对应 record。）

- [ ] **Step 2: 写失败测试** `WeekRangeParserTest.java`

```java
package com.pams.module.schedule;

import com.pams.module.schedule.generator.WeekRangeParser;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WeekRangeParserTest {

    private static Set<Integer> w(int... vals) {
        Set<Integer> s = new HashSet<>();
        for (int v : vals) s.add(v);
        return s;
    }

    @Test
    void fullRange() {
        assertThat(WeekRangeParser.parse("1-16", 18)).isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16));
    }

    @Test
    void singleOddWeeks() {
        assertThat(WeekRangeParser.parse("1-13 单", 18)).isEqualTo(w(1,3,5,7,9,11,13));
    }

    @Test
    void singleEvenWeeks() {
        assertThat(WeekRangeParser.parse("2-18 双", 18)).isEqualTo(w(2,4,6,8,10,12,14,16,18));
    }

    @Test
    void specificWeeksSemicolon() {
        assertThat(WeekRangeParser.parse("3;7", 18)).isEqualTo(w(3,7));
    }

    @Test
    void specificWeeksComma() {
        assertThat(WeekRangeParser.parse("1,3,5", 18)).isEqualTo(w(1,3,5));
    }

    @Test
    void mixedSegments() {
        assertThat(WeekRangeParser.parse("3-6;11-18", 18)).isEqualTo(w(3,4,5,6,11,12,13,14,15,16,17,18));
    }

    @Test
    void mixedWithParity() {
        assertThat(WeekRangeParser.parse("1-4;9-18", 18)).isEqualTo(w(1,2,3,4,9,10,11,12,13,14,15,16,17,18));
    }

    @Test
    void singleWeek() {
        assertThat(WeekRangeParser.parse("8", 18)).isEqualTo(w(8));
    }

    @Test
    void truncateAtMaxWeek() {
        assertThat(WeekRangeParser.parse("1-20", 18)).isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18));
    }

    @Test
    void blank_returnsEmpty() {
        assertThat(WeekRangeParser.parse("", 18)).isEmpty();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=WeekRangeParserTest`
Expected: FAIL，`WeekRangeParser` 编译不存在（COMPILATION ERROR）。

- [ ] **Step 4: 实现 `WeekRangeParser.java`**

```java
package com.pams.module.schedule.generator;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析课程周次字符串为有课周集合。
 * 支持 "1-16"、"1-13 单"、"2-18 双"、"3;7"、"1,3,5"、"3-6;11-18"、"1-4;9-18"、"8"；
 * 按 maxWeek 截断（超过的周丢弃）。
 */
public final class WeekRangeParser {

    private static final Pattern SEG = Pattern.compile("(\\d+)(?:-(\\d+))?\\s*(单|双)?");

    private WeekRangeParser() {}

    public static Set<Integer> parse(String weekText, int maxWeek) {
        Set<Integer> result = new HashSet<>();
        if (weekText == null || weekText.isBlank()) return result;
        for (String seg : weekText.replace(',', ';').split(";")) {
            seg = seg.trim();
            if (seg.isEmpty()) continue;
            Matcher m = SEG.matcher(seg);
            if (!m.matches()) continue;
            int a = Integer.parseInt(m.group(1));
            int b = m.group(2) == null ? a : Integer.parseInt(m.group(2));
            if (b < a) { int t = a; a = b; b = t; }
            String parity = m.group(3);
            for (int w = a; w <= b && w <= maxWeek; w++) {
                if (parity == null) result.add(w);
                else if ("单".equals(parity) && w % 2 == 1) result.add(w);
                else if ("双".equals(parity) && w % 2 == 0) result.add(w);
            }
        }
        return result;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=WeekRangeParserTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/generator/Course.java pams-backend/src/main/java/com/pams/module/schedule/generator/SlotKey.java pams-backend/src/main/java/com/pams/module/schedule/generator/PersonTimetable.java pams-backend/src/main/java/com/pams/module/schedule/generator/NoClassScheduleCell.java pams-backend/src/main/java/com/pams/module/schedule/generator/NoClassScheduleRow.java pams-backend/src/main/java/com/pams/module/schedule/generator/WeekRangeParser.java pams-backend/src/test/java/com/pams/module/schedule/WeekRangeParserTest.java
git commit -m "feat(schedule): add timetable model records and WeekRangeParser"
```

---

## Task 2: `FreeWeekFormatter`

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/FreeWeekFormatter.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/FreeWeekFormatterTest.java`

**Interfaces:**
- Consumes: `Set<Integer>`（无课周集合）
- Produces: `FreeWeekFormatter.format(Set<Integer>) → String`（空集→`0`；连续段`1-4`；步长2段`9-15 单`/`2-8 双`；逗号拼接；全集→`1-18`）

- [ ] **Step 1: 写失败测试**

```java
package com.pams.module.schedule;

import com.pams.module.schedule.generator.FreeWeekFormatter;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FreeWeekFormatterTest {

    private static Set<Integer> w(int... vals) {
        Set<Integer> s = new HashSet<>();
        for (int v : vals) s.add(v);
        return s;
    }

    @Test
    void empty_isZero() {
        assertThat(FreeWeekFormatter.format(Set.of())).isEqualTo("0");
    }

    @Test
    void contiguous() {
        assertThat(FreeWeekFormatter.format(w(1,2,3,4))).isEqualTo("1-4");
    }

    @Test
    void oddRun() {
        assertThat(FreeWeekFormatter.format(w(9,11,13,15))).isEqualTo("9-15 单");
    }

    @Test
    void evenRun() {
        assertThat(FreeWeekFormatter.format(w(2,4,6,8))).isEqualTo("2-8 双");
    }

    @Test
    void contiguousThenEvenRun() {
        assertThat(FreeWeekFormatter.format(w(1,2,4,6))).isEqualTo("1-2,4-6 双");
    }

    @Test
    void oddRunThenContiguous() {
        assertThat(FreeWeekFormatter.format(w(9,11,13,15,17,18))).isEqualTo("9-17 单,18");
    }

    @Test
    void full() {
        assertThat(FreeWeekFormatter.format(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18))).isEqualTo("1-18");
    }

    @Test
    void scattered() {
        assertThat(FreeWeekFormatter.format(w(3,7))).isEqualTo("3,7");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=FreeWeekFormatterTest`
Expected: FAIL（COMPILATION ERROR）。

- [ ] **Step 3: 实现**

```java
package com.pams.module.schedule.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 将无课周集合格式化为展示串。
 * 规则：连续段 "1-4"；步长 2 的段标注 "9-15 单"/"2-8 双"；多段逗号拼接；空集 -> "0"。
 */
public final class FreeWeekFormatter {

    private FreeWeekFormatter() {}

    public static String format(Set<Integer> weeks) {
        List<Integer> list = weeks.stream().sorted().toList();
        if (list.isEmpty()) return "0";
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < list.size()) {
            int j = i; // 最长步长1连续段
            while (j + 1 < list.size() && list.get(j + 1) - list.get(j) == 1) j++;
            int k = i; // 最长步长2连续段
            while (k + 1 < list.size() && list.get(k + 1) - list.get(k) == 2) k++;
            if (k - i >= 1 && k - i >= j - i) {
                String tag = (list.get(i) % 2 == 1) ? " 单" : " 双";
                parts.add(list.get(i) + "-" + list.get(k) + tag);
                i = k + 1;
            } else if (j - i >= 1) {
                parts.add(list.get(i) + "-" + list.get(j));
                i = j + 1;
            } else {
                parts.add(String.valueOf(list.get(i)));
                i++;
            }
        }
        return String.join(",", parts);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=FreeWeekFormatterTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/generator/FreeWeekFormatter.java pams-backend/src/test/java/com/pams/module/schedule/FreeWeekFormatterTest.java
git commit -m "feat(schedule): add FreeWeekFormatter for segmented free-week display"
```

---

## Task 3: `FreeWeekCalculator`

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/FreeWeekCalculator.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/FreeWeekCalculatorTest.java`

**Interfaces:**
- Consumes: `List<Course>`、`Map<SlotKey, List<Course>>`
- Produces: `FreeWeekCalculator.freeWeeksForSlot(List<Course>, int maxWeek) → Set<Integer>`；`FreeWeekCalculator.compute(Map<SlotKey, List<Course>>, int maxWeek) → Map<SlotKey, Set<Integer>>`（覆盖 7 天 × 6 节，缺省时段=全集空闲）

- [ ] **Step 1: 写失败测试**

```java
package com.pams.module.schedule;

import com.pams.module.schedule.generator.Course;
import com.pams.module.schedule.generator.FreeWeekCalculator;
import com.pams.module.schedule.generator.SlotKey;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FreeWeekCalculatorTest {

    private static Set<Integer> w(int... vals) {
        Set<Integer> s = new HashSet<>();
        for (int v : vals) s.add(v);
        return s;
    }

    @Test
    void freeIsComplementOfUnion() {
        List<Course> courses = List.of(new Course("A", w(1,2,3,4)), new Course("B", w(4,5,6)));
        assertThat(FreeWeekCalculator.freeWeeksForSlot(courses, 18))
                .isEqualTo(w(7,8,9,10,11,12,13,14,15,16,17,18));
    }

    @Test
    void emptyCourses_allFree() {
        assertThat(FreeWeekCalculator.freeWeeksForSlot(List.of(), 18))
                .isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18));
    }

    @Test
    void computeCoversAllSlots() {
        Map<SlotKey, List<Course>> tt = Map.of(new SlotKey(1, 1), List.of(new Course("A", w(1))));
        Map<SlotKey, Set<Integer>> free = FreeWeekCalculator.compute(tt, 18);
        assertThat(free.get(new SlotKey(1, 1))).doesNotContain(1);
        assertThat(free.get(new SlotKey(3, 5)))
                .isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=FreeWeekCalculatorTest`
Expected: FAIL（COMPILATION ERROR）。

- [ ] **Step 3: 实现**

```java
package com.pams.module.schedule.generator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 每(星期,节次)时段的空闲周 = 全集{1..maxWeek} - 该时段所有课程有课周并集。 */
public final class FreeWeekCalculator {

    private FreeWeekCalculator() {}

    public static Set<Integer> freeWeeksForSlot(List<Course> courses, int maxWeek) {
        Set<Integer> all = new HashSet<>();
        for (int w = 1; w <= maxWeek; w++) all.add(w);
        for (Course c : courses) all.removeAll(c.weeks());
        return all;
    }

    /** 返回每(星期,节次)的空闲周集合（7 天 × 6 节全覆盖，缺省时段视为全集空闲）。 */
    public static Map<SlotKey, Set<Integer>> compute(Map<SlotKey, List<Course>> timetable, int maxWeek) {
        Map<SlotKey, Set<Integer>> result = new HashMap<>();
        for (int day = 1; day <= 7; day++) {
            for (int period = 1; period <= 6; period++) {
                SlotKey key = new SlotKey(day, period);
                result.put(key, freeWeeksForSlot(timetable.getOrDefault(key, List.of()), maxWeek));
            }
        }
        return result;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=FreeWeekCalculatorTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/generator/FreeWeekCalculator.java pams-backend/src/test/java/com/pams/module/schedule/FreeWeekCalculatorTest.java
git commit -m "feat(schedule): add FreeWeekCalculator for complement computation"
```

---

## Task 4: `TimetableNameExtractor`

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/TimetableNameExtractor.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/TimetableNameExtractorTest.java`

**Interfaces:**
- Produces: `TimetableNameExtractor.extractName(String filename) → String`（解析不出返回 null）

- [ ] **Step 1: 写失败测试**

```java
package com.pams.module.schedule;

import com.pams.module.schedule.generator.TimetableNameExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimetableNameExtractorTest {

    @Test
    void dashFile() {
        assertThat(TimetableNameExtractor.extractName("张子睿-文件-2025物联网3班-班级课表.xlsx")).isEqualTo("张子睿");
    }

    @Test
    void underscore() {
        assertThat(TimetableNameExtractor.extractName("刘如倩_2025计应2班-班级课表.xlsx")).isEqualTo("刘如倩");
    }

    @Test
    void dashNoFile() {
        assertThat(TimetableNameExtractor.extractName("罗展标-2025软件技术2班.xlsx")).isEqualTo("罗展标");
    }

    @Test
    void leadingChinese() {
        assertThat(TimetableNameExtractor.extractName("凌健锦2025软件3班.xlsx")).isEqualTo("凌健锦");
    }

    @Test
    void leadingChineseFour() {
        assertThat(TimetableNameExtractor.extractName("司徒锦豪2025物联网3班-班级课表.xlsx")).isEqualTo("司徒锦豪");
    }

    @Test
    void personalFormat() {
        assertThat(TimetableNameExtractor.extractName("吴文烨-文件-25计应创新2班吴文烨.xlsx")).isEqualTo("吴文烨");
    }

    @Test
    void unparseable_returnsNull() {
        assertThat(TimetableNameExtractor.extractName("2025物联网3班.xlsx")).isNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=TimetableNameExtractorTest`
Expected: FAIL（COMPILATION ERROR）。

- [ ] **Step 3: 实现**

```java
package com.pams.module.schedule.generator;

import java.util.regex.Pattern;

/** 从文件名提取干事姓名：优先「姓名-文件-」，其次「姓名_/-」，最后前导中文。提取失败返回 null。 */
public final class TimetableNameExtractor {

    private static final Pattern SUFFIX = Pattern.compile("(?i)\\.(xlsx|xls)$");
    private static final Pattern DASH_FILE = Pattern.compile("^(.*?)-文件-");
    private static final Pattern LEADING_CN = Pattern.compile("^[\\u4e00-\\u9fa5·]{2,4}");

    private TimetableNameExtractor() {}

    public static String extractName(String filename) {
        if (filename == null) return null;
        String base = SUFFIX.matcher(filename.trim()).replaceFirst("");
        var m = DASH_FILE.matcher(base);
        if (m.find() && looksLikeName(m.group(1).trim())) return m.group(1).trim();
        int cut = -1;
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            if (ch == '-' || ch == '_') { cut = i; break; }
        }
        if (cut > 0) {
            String first = base.substring(0, cut).trim();
            if (looksLikeName(first)) return first;
        }
        var lm = LEADING_CN.matcher(base);
        if (lm.find() && looksLikeName(lm.group())) return lm.group();
        return null;
    }

    private static boolean looksLikeName(String s) {
        return s != null && !s.isEmpty() && s.length() <= 6 && s.matches("[\\u4e00-\\u9fa5·]+");
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=TimetableNameExtractorTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/generator/TimetableNameExtractor.java pams-backend/src/test/java/com/pams/module/schedule/TimetableNameExtractorTest.java
git commit -m "feat(schedule): add TimetableNameExtractor from filename"
```

---

## Task 5: `ClassTimetableParser` + 共享 fixture

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/ClassTimetableParser.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/ClassTimetableParserTest.java`（含供后续任务复用的 `buildTimetable()`）

**Interfaces:**
- Consumes: `Course`、`SlotKey`、`WeekRangeParser`
- Produces:
  - `ClassTimetableParser.parse(InputStream) → Map<SlotKey, List<Course>>`（非课表格式抛 `IllegalArgumentException`）
  - `ClassTimetableParser.detectSemester(InputStream) → String`（从首个 sheet 名取 `2025-2026-2`，取不到返回 null）
  - `ClassTimetableParserTest.buildTimetable() → byte[]`（构造与真实班级课表一致的 9 行网格，供 Task 6-9 复用）

- [ ] **Step 1: 写失败测试（含 fixture）**

```java
package com.pams.module.schedule;

import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.Course;
import com.pams.module.schedule.generator.SlotKey;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClassTimetableParserTest {

    static Set<Integer> w(int... vals) {
        Set<Integer> s = new HashSet<>();
        for (int v : vals) s.add(v);
        return s;
    }

    /** 构造与真实班级课表一致的 9 行网格（标题 + 表头 + 周一~周日 + 6 行节次）。 */
    static byte[] buildTimetable() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("2025-2026-2课表");
            String[][] rows = {
                    {"班级代码：25351010203   院系：信息工程学院", null, null, null, "测试一班 课表信息", null, null, "2025-2026学年第2学期", null},
                    {"节次", null, "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"},
                    {"上午", "第1 2节", "课程A(1-16)[1,2]◇1101◇老师甲", "课程B(1-13 单)[1,2]◇2101◇老师乙", "课程C(2-18 双)[1,2]◇3101◇老师丙", null, null, null, null},
                    {"上午", "第3 4节", "课程D(3;7)[3,4]◇1101◇老师甲", "课程E(3-6;11-18)[3,4]◇2101◇老师乙", null, null, "课程F(1-18)[3,4]◇3101◇老师丙", null, null},
                    {"下午", "第5 6节", "课程G(1-4;9-18)[5,6]◇1101◇老师甲", null, null, null, null, null, null},
                    {"下午", "第7 8节", null, "课程H(2-14 双)[7,8]◇2101◇老师乙", null, null, null, null, null},
                    {"晚上", "第9 10节", "公共选修课(1-16)[9,10]◇预占位◇预占位", null, null, null, null, null, null},
                    {"晚上", "第11 12节", null, null, null, "课程I(8)[11,12]◇2101◇老师乙", null, null, null},
                    {"教室编号说明…", null, null, null, null, null, null, "2026-03-06", null},
            };
            for (int i = 0; i < rows.length; i++) {
                Row row = sheet.createRow(i);
                for (int j = 0; j < rows[i].length; j++) {
                    if (rows[i][j] != null) row.createCell(j).setCellValue(rows[i][j]);
                }
            }
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                return out.toByteArray();
            }
        }
    }

    @Test
    void parsesGrid() throws Exception {
        Map<SlotKey, List<Course>> tt = ClassTimetableParser.parse(new ByteArrayInputStream(buildTimetable()));
        assertThat(tt.get(new SlotKey(1, 1))).hasSize(1);
        assertThat(tt.get(new SlotKey(1, 1)).get(0).name()).isEqualTo("课程A");
        assertThat(tt.get(new SlotKey(1, 1)).get(0).weeks()).isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16));
        assertThat(tt.get(new SlotKey(2, 1)).get(0).weeks()).isEqualTo(w(1,3,5,7,9,11,13));
        assertThat(tt.get(new SlotKey(3, 1)).get(0).weeks()).isEqualTo(w(2,4,6,8,10,12,14,16,18));
        assertThat(tt.get(new SlotKey(2, 2)).get(0).weeks()).isEqualTo(w(3,4,5,6,11,12,13,14,15,16,17,18));
        assertThat(tt.get(new SlotKey(5, 2)).get(0).weeks()).isEqualTo(w(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18));
        assertThat(tt.get(new SlotKey(4, 6)).get(0).weeks()).isEqualTo(w(8));
    }

    @Test
    void detectsSemester() throws Exception {
        assertThat(ClassTimetableParser.detectSemester(new ByteArrayInputStream(buildTimetable()))).isEqualTo("2025-2026-2");
    }

    @Test
    void throwsOnNonTimetable() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("x").createRow(0).createCell(0).setCellValue("随便");
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                wb.write(out);
                assertThrows(IllegalArgumentException.class,
                        () -> ClassTimetableParser.parse(new ByteArrayInputStream(out.toByteArray())));
            }
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=ClassTimetableParserTest`
Expected: FAIL（COMPILATION ERROR）。

- [ ] **Step 3: 实现**

```java
package com.pams.module.schedule.generator;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取班级课表 Excel（"节次"表头 + 周一到周日列 + 6 行节次），解析出每(星期,节次)的课程列表。
 * 单元格格式：课程名(周次)[节次]◇教室◇教师，多课用换行分隔。
 */
public final class ClassTimetableParser {

    /** 取紧跟 "[数字,数字]" 的括号内容作为周次，防课程名内括号干扰。 */
    private static final Pattern COURSE = Pattern.compile("\\(([^()]*)\\)\\s*\\[[\\d,\\s]+\\]");
    private static final Pattern SEMESTER = Pattern.compile("\\b(\\d{4}-\\d{4}-\\d)\\b");
    private static final String[] DAY_NAMES = {"星期一","星期二","星期三","星期四","星期五","星期六","星期日"};

    private ClassTimetableParser() {}

    public static Map<SlotKey, List<Course>> parse(InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            return parseSheet(wb.getSheetAt(0));
        }
    }

    public static String detectSemester(InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Matcher m = SEMESTER.matcher(wb.getSheetAt(0).getSheetName());
            return m.find() ? m.group(1) : null;
        }
    }

    private static Map<SlotKey, List<Course>> parseSheet(Sheet sheet) {
        DataFormatter fmt = new DataFormatter();
        int headerRow = -1;
        for (int r = 0; r <= Math.min(5, sheet.getLastRowNum()); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                if ("节次".equals(fmt.formatCellValue(row.getCell(c)).trim())) { headerRow = r; break; }
            }
            if (headerRow >= 0) break;
        }
        if (headerRow < 0) throw new IllegalArgumentException("未找到“节次”表头，不是班级课表格式");

        Map<Integer, Integer> dayCol = new HashMap<>();
        Row hdr = sheet.getRow(headerRow);
        for (int c = 0; c < hdr.getLastCellNum(); c++) {
            String v = fmt.formatCellValue(hdr.getCell(c)).trim();
            for (int d = 1; d <= 7; d++) {
                if (DAY_NAMES[d - 1].equals(v)) dayCol.put(d, c);
            }
        }

        Map<SlotKey, List<Course>> result = new HashMap<>();
        for (int period = 1; period <= 6; period++) {
            Row row = sheet.getRow(headerRow + period);
            if (row == null) continue;
            for (int d = 1; d <= 7; d++) {
                Integer col = dayCol.get(d);
                if (col == null) continue;
                List<Course> courses = parseCell(fmt.formatCellValue(row.getCell(col)));
                if (!courses.isEmpty()) result.put(new SlotKey(d, period), courses);
            }
        }
        return result;
    }

    private static List<Course> parseCell(String text) {
        List<Course> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Matcher m = COURSE.matcher(trimmed);
            if (!m.find()) continue;
            String name = trimmed.substring(0, m.start()).trim();
            if (name.isEmpty()) name = "未知课程";
            out.add(new Course(name, WeekRangeParser.parse(m.group(1).trim(), 18)));
        }
        return out;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=ClassTimetableParserTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/generator/ClassTimetableParser.java pams-backend/src/test/java/com/pams/module/schedule/ClassTimetableParserTest.java
git commit -m "feat(schedule): add ClassTimetableParser for xlsx grid parsing"
```

---

## Task 6: `NoClassScheduleGenerator`

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/NoClassScheduleGenerator.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/NoClassScheduleGeneratorTest.java`

**Interfaces:**
- Consumes: `PersonTimetable`、`NoClassScheduleRow`、`NoClassScheduleCell`、`FreeWeekCalculator`、`FreeWeekFormatter`、`ClassTimetableParserTest.buildTimetable()`
- Produces: `NoClassScheduleGenerator.build(List<PersonTimetable>, int maxWeek) → List<NoClassScheduleRow>`（6 行，每行 cells 为 day(1-5)→排序后的 `NoClassScheduleCell`）；常量 `PERIOD_LABELS`、`HALF_DAYS`

- [ ] **Step 1: 写失败测试**

```java
package com.pams.module.schedule;

import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.Course;
import com.pams.module.schedule.generator.NoClassScheduleCell;
import com.pams.module.schedule.generator.NoClassScheduleGenerator;
import com.pams.module.schedule.generator.NoClassScheduleRow;
import com.pams.module.schedule.generator.PersonTimetable;
import com.pams.module.schedule.generator.SlotKey;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NoClassScheduleGeneratorTest {

    @Test
    void buildsGridFromParsedTimetable() throws Exception {
        Map<SlotKey, List<Course>> tt = ClassTimetableParser.parse(new ByteArrayInputStream(ClassTimetableParserTest.buildTimetable()));
        List<NoClassScheduleRow> rows = NoClassScheduleGenerator.build(List.of(new PersonTimetable("张三", tt)), 18);

        assertThat(rows).hasSize(6);
        NoClassScheduleRow p1 = rows.get(0);
        assertThat(p1.label()).isEqualTo("第一二节");
        assertThat(p1.halfDay()).isEqualTo("上午");
        // 周一第1-2节：课程A(1-16) -> 空闲 17-18
        assertThat(p1.cells().get(1)).containsExactly(new NoClassScheduleCell("张三", "17-18"));
        // 周二第1-2节：课程B(1-13 单) -> 空闲 2-14 双,15-18
        assertThat(p1.cells().get(2)).containsExactly(new NoClassScheduleCell("张三", "2-14 双,15-18"));
        // 周三第1-2节：课程C(2-18 双) -> 空闲 1-17 单
        assertThat(p1.cells().get(3)).containsExactly(new NoClassScheduleCell("张三", "1-17 单"));
        // 周五第3-4节：课程F(1-18) -> 满课 0
        assertThat(rows.get(1).cells().get(5)).containsExactly(new NoClassScheduleCell("张三", "0"));
        // 周一第9-10节：公共选修课(1-16) -> 17-18
        assertThat(rows.get(4).cells().get(1)).containsExactly(new NoClassScheduleCell("张三", "17-18"));
        // 周四第11-12节：课程I(8) -> 1-7,9-18
        assertThat(rows.get(5).cells().get(4)).containsExactly(new NoClassScheduleCell("张三", "1-7,9-18"));
    }

    @Test
    void sortsCellsByNameAscending() {
        Map<SlotKey, List<Course>> empty = new HashMap<>();
        List<NoClassScheduleRow> rows = NoClassScheduleGenerator.build(
                List.of(new PersonTimetable("李四", empty), new PersonTimetable("张三", empty)), 18);
        // "张"(U+5F20) < "李"(U+674E)，故张三在前
        assertThat(rows.get(0).cells().get(1))
                .containsExactly(new NoClassScheduleCell("张三", "1-18"), new NoClassScheduleCell("李四", "1-18"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=NoClassScheduleGeneratorTest`
Expected: FAIL（COMPILATION ERROR）。

- [ ] **Step 3: 实现**

```java
package com.pams.module.schedule.generator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 把多个人的课程表组装成"无课表"网格：6 行节次 x 周一~周五，每格列出每人空闲周。 */
public final class NoClassScheduleGenerator {

    public static final String[] PERIOD_LABELS = {"第一二节","第三四节","第五六节","第七八节","第九十节","第十一十二节"};
    public static final String[] HALF_DAYS = {"上午","上午","下午","下午","晚上","晚上"};

    private NoClassScheduleGenerator() {}

    public static List<NoClassScheduleRow> build(List<PersonTimetable> people, int maxWeek) {
        List<NoClassScheduleRow> rows = new ArrayList<>();
        for (int period = 1; period <= 6; period++) {
            Map<Integer, List<NoClassScheduleCell>> cells = new TreeMap<>();
            for (int day = 1; day <= 5; day++) {
                List<NoClassScheduleCell> dayCells = new ArrayList<>();
                for (PersonTimetable p : people) {
                    List<Course> courses = p.timetable().getOrDefault(new SlotKey(day, period), List.of());
                    String text = FreeWeekFormatter.format(FreeWeekCalculator.freeWeeksForSlot(courses, maxWeek));
                    dayCells.add(new NoClassScheduleCell(p.name(), text));
                }
                dayCells.sort(Comparator.comparing(NoClassScheduleCell::name));
                cells.put(day, dayCells);
            }
            rows.add(new NoClassScheduleRow(period, PERIOD_LABELS[period - 1], HALF_DAYS[period - 1], cells));
        }
        return rows;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=NoClassScheduleGeneratorTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/generator/NoClassScheduleGenerator.java pams-backend/src/test/java/com/pams/module/schedule/NoClassScheduleGeneratorTest.java
git commit -m "feat(schedule): add NoClassScheduleGenerator grid assembly"
```

---

## Task 7: `NoClassScheduleExcelWriter`

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/NoClassScheduleExcelWriter.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/NoClassScheduleExcelWriterTest.java`

**Interfaces:**
- Consumes: `List<NoClassScheduleRow>`
- Produces: `NoClassScheduleExcelWriter.write(List<NoClassScheduleRow>, String title, OutputStream) → void`（标题行 A1:G1 合并；表头 `节次|星期一…星期五`；6 行节次，A 列上午/下午/晚上按行合并，每格 `姓名（无课周次）` 换行）

- [ ] **Step 1: 写失败测试**

```java
package com.pams.module.schedule;

import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.NoClassScheduleExcelWriter;
import com.pams.module.schedule.generator.NoClassScheduleGenerator;
import com.pams.module.schedule.generator.NoClassScheduleRow;
import com.pams.module.schedule.generator.PersonTimetable;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoClassScheduleExcelWriterTest {

    private static List<NoClassScheduleRow> grid() throws Exception {
        var tt = ClassTimetableParser.parse(new ByteArrayInputStream(ClassTimetableParserTest.buildTimetable()));
        return NoClassScheduleGenerator.build(List.of(new PersonTimetable("张三", tt)), 18);
    }

    @Test
    void writesExpectedLayout() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NoClassScheduleExcelWriter.write(grid(), "文秘部 无课表", out);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("文秘部");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("星期一");
            Row p1 = sheet.getRow(2);
            assertThat(p1.getCell(1).getStringCellValue()).isEqualTo("第一二节");
            assertThat(p1.getCell(2).getStringCellValue()).contains("张三").contains("17-18");
            Row p6 = sheet.getRow(7);
            assertThat(p6.getCell(1).getStringCellValue()).isEqualTo("第十一十二节");
            assertThat(p6.getCell(5).getStringCellValue()).contains("1-7,9-18");
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=NoClassScheduleExcelWriterTest`
Expected: FAIL（COMPILATION ERROR）。

- [ ] **Step 3: 实现**

```java
package com.pams.module.schedule.generator;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellRangeAddress;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** 把无课表网格写成 xlsx（版式与部门现有无课表一致：标题 + 周一~周五 + 6 行节次）。 */
public final class NoClassScheduleExcelWriter {

    private NoClassScheduleExcelWriter() {}

    public static void write(List<NoClassScheduleRow> rows, String title, OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("无课表");

            CellStyle titleStyle = wb.createCellStyle();
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);
            titleStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle cellStyle = wb.createCellStyle();
            cellStyle.setBorderTop(BorderStyle.THIN);
            cellStyle.setBorderBottom(BorderStyle.THIN);
            cellStyle.setBorderLeft(BorderStyle.THIN);
            cellStyle.setBorderRight(BorderStyle.THIN);
            cellStyle.setWrapText(true);
            cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            Row t = sheet.createRow(0);
            Cell tc = t.createCell(0);
            tc.setCellValue(title);
            tc.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

            String[] headers = {"节次", "星期一", "星期二", "星期三", "星期四", "星期五"};
            Row h = sheet.createRow(1);
            for (int c = 0; c < headers.length; c++) {
                Cell cell = h.createCell(c);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(cellStyle);
            }

            int r = 2;
            for (NoClassScheduleRow row : rows) {
                Row rr = sheet.createRow(r);
                rr.createCell(0).setCellValue(row.halfDay());
                rr.createCell(1).setCellValue(row.label());
                for (int day = 1; day <= 5; day++) {
                    List<NoClassScheduleCell> cells = row.cells().get(day);
                    Cell cell = rr.createCell(day + 1);
                    cell.setCellValue(joinCells(cells));
                    cell.setCellStyle(cellStyle);
                }
                r++;
            }

            sheet.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(4, 5, 0, 0));
            sheet.addMergedRegion(new CellRangeAddress(6, 7, 0, 0));

            sheet.setColumnWidth(0, 8 * 256);
            for (int c = 1; c <= 6; c++) sheet.setColumnWidth(c, 30 * 256);

            wb.write(out);
        }
    }

    private static String joinCells(List<NoClassScheduleCell> cells) {
        if (cells == null || cells.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (NoClassScheduleCell c : cells) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(c.name()).append("（").append(c.freeWeeks()).append("）");
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=NoClassScheduleExcelWriterTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/generator/NoClassScheduleExcelWriter.java pams-backend/src/test/java/com/pams/module/schedule/NoClassScheduleExcelWriterTest.java
git commit -m "feat(schedule): add NoClassScheduleExcelWriter for xlsx output"
```

---

## Task 8: `NoClassScheduleMarkdownWriter`

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/schedule/generator/NoClassScheduleMarkdownWriter.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/NoClassScheduleMarkdownWriterTest.java`

**Interfaces:**
- Consumes: `List<NoClassScheduleRow>`
- Produces: `NoClassScheduleMarkdownWriter.write(List<NoClassScheduleRow>, String title) → String`（`# 标题` + `节次|星期一…星期五` 表格，每格 `姓名（无课周次）` 用 `<br>` 分隔）

- [ ] **Step 1: 写失败测试**

```java
package com.pams.module.schedule;

import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.NoClassScheduleGenerator;
import com.pams.module.schedule.generator.NoClassScheduleMarkdownWriter;
import com.pams.module.schedule.generator.NoClassScheduleRow;
import com.pams.module.schedule.generator.PersonTimetable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoClassScheduleMarkdownWriterTest {

    @Test
    void writesMarkdownTable() throws Exception {
        var tt = ClassTimetableParser.parse(new ByteArrayInputStream(ClassTimetableParserTest.buildTimetable()));
        List<NoClassScheduleRow> rows = NoClassScheduleGenerator.build(List.of(new PersonTimetable("张三", tt)), 18);
        String md = NoClassScheduleMarkdownWriter.write(rows, "文秘部 无课表");

        assertThat(md).contains("# 文秘部 无课表");
        assertThat(md).contains("| 节次 | 星期一 | 星期二 | 星期三 | 星期四 | 星期五 |");
        assertThat(md).contains("| 第一二节 | 张三（17-18） | 张三（2-14 双,15-18） | 张三（1-17 单） |");
        assertThat(md).contains("| 第十一十二节 | 张三（1-7,9-18）");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=NoClassScheduleMarkdownWriterTest`
Expected: FAIL（COMPILATION ERROR）。

- [ ] **Step 3: 实现**

```java
package com.pams.module.schedule.generator;

import java.util.List;

/** 把无课表网格写成 Markdown 表格，便于对话/文档直接展示。 */
public final class NoClassScheduleMarkdownWriter {

    private NoClassScheduleMarkdownWriter() {}

    public static String write(List<NoClassScheduleRow> rows, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null ? "无课表" : title).append("\n\n");
        sb.append("| 节次 | 星期一 | 星期二 | 星期三 | 星期四 | 星期五 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (NoClassScheduleRow row : rows) {
            sb.append("| ").append(row.label());
            for (int day = 1; day <= 5; day++) {
                sb.append(" | ").append(joinCells(row.cells().get(day)));
            }
            sb.append(" |\n");
        }
        return sb.toString();
    }

    private static String joinCells(List<NoClassScheduleCell> cells) {
        if (cells == null || cells.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (NoClassScheduleCell c : cells) {
            if (sb.length() > 0) sb.append("<br>");
            sb.append(c.name()).append("（").append(c.freeWeeks()).append("）");
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=NoClassScheduleMarkdownWriterTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/generator/NoClassScheduleMarkdownWriter.java pams-backend/src/test/java/com/pams/module/schedule/NoClassScheduleMarkdownWriterTest.java
git commit -m "feat(schedule): add NoClassScheduleMarkdownWriter for markdown preview"
```

---

## Task 9: DTO + `NoClassScheduleImportService`

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/schedule/dto/NoClassScheduleCellVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/dto/NoClassScheduleRowVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/dto/ImportFileFailureVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/dto/NoClassScheduleImportVO.java`
- Create: `pams-backend/src/main/java/com/pams/module/schedule/service/NoClassScheduleImportService.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/NoClassScheduleImportServiceTest.java`

**Interfaces:**
- Consumes: 全部 generator 类、`DepartmentRepository`、`Department`、DTO；`ClassTimetableParserTest.buildTimetable()`
- Produces:
  - `NoClassScheduleImportService.importTimetables(List<MultipartFile>, Long deptId, String semester, Integer maxWeek) → NoClassScheduleImportVO`
  - `NoClassScheduleImportService.resolveDownload(String path) → Path`（校验 path 归一化后在 uploadDir 内，否则抛 BizException 2705）
  - VO 字段：`deptName, semester, rows(List<NoClassScheduleRowVO>), markdown, downloadUrl, totalFiles, successCount, failed(List<ImportFileFailureVO>), warnings(List<String>)`；`NoClassScheduleRowVO`：`period, label, halfDay, days(Map<String day, List<NoClassScheduleCellVO>>)`

- [ ] **Step 1: 写 4 个 DTO**

```java
package com.pams.module.schedule.dto;

import lombok.Data;

@Data
public class NoClassScheduleCellVO {
    private String name;
    private String freeWeeks;
}
```

```java
package com.pams.module.schedule.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class NoClassScheduleRowVO {
    private int period;
    private String label;
    private String halfDay;
    /** day "1".."5" -> 该列人员 */
    private Map<String, List<NoClassScheduleCellVO>> days;
}
```

```java
package com.pams.module.schedule.dto;

import lombok.Data;

@Data
public class ImportFileFailureVO {
    private String fileName;
    private String reason;
}
```

```java
package com.pams.module.schedule.dto;

import lombok.Data;
import java.util.List;

@Data
public class NoClassScheduleImportVO {
    private String deptName;
    private String semester;
    private List<NoClassScheduleRowVO> rows;
    private String markdown;
    private String downloadUrl;
    private int totalFiles;
    private int successCount;
    private List<ImportFileFailureVO> failed;
    private List<String> warnings;
}
```

- [ ] **Step 2: 写失败测试**

```java
package com.pams.module.schedule;

import com.pams.entity.Department;
import com.pams.module.schedule.dto.NoClassScheduleImportVO;
import com.pams.module.schedule.service.NoClassScheduleImportService;
import com.pams.repository.DepartmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NoClassScheduleImportServiceTest {

    @Test
    void importsFilesAndWritesOutput(@TempDir Path tempDir) throws Exception {
        DepartmentRepository deptRepo = mock(DepartmentRepository.class);
        Department dept = new Department();
        dept.setId(1L);
        dept.setName("文秘部");
        when(deptRepo.findById(1L)).thenReturn(Optional.of(dept));

        NoClassScheduleImportService service = new NoClassScheduleImportService(deptRepo);
        service.setUploadDir(tempDir.toString());

        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-2025物联网3班-班级课表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ClassTimetableParserTest.buildTimetable());

        NoClassScheduleImportVO vo = service.importTimetables(List.of(f), 1L, "2025-2026-2", null);

        assertThat(vo.getDeptName()).isEqualTo("文秘部");
        assertThat(vo.getSuccessCount()).isEqualTo(1);
        assertThat(vo.getTotalFiles()).isEqualTo(1);
        assertThat(vo.getFailed()).isEmpty();
        assertThat(vo.getRows()).hasSize(6);
        assertThat(vo.getRows().get(0).getDays().get("1").get(0).getFreeWeeks()).isEqualTo("17-18");
        assertThat(vo.getMarkdown()).contains("张三（17-18）");
        assertThat(vo.getDownloadUrl()).startsWith("无课表/").endsWith(".xlsx");
        assertThat(tempDir.resolve(vo.getDownloadUrl())).exists();
    }

    @Test
    void allFilesFailed_returnsZeroSuccess() {
        NoClassScheduleImportService service = new NoClassScheduleImportService(mock(DepartmentRepository.class));
        MockMultipartFile f = new MockMultipartFile("files", "2025物联网3班.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{});
        NoClassScheduleImportVO vo = service.importTimetables(List.of(f), null, null, null);
        assertThat(vo.getSuccessCount()).isZero();
        assertThat(vo.getFailed()).hasSize(1);
        assertThat(vo.getFailed().get(0).getReason()).contains("姓名");
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=NoClassScheduleImportServiceTest`
Expected: FAIL（COMPILATION ERROR）。

- [ ] **Step 4: 实现 Service**

```java
package com.pams.module.schedule.service;

import com.pams.common.BizException;
import com.pams.entity.Department;
import com.pams.module.schedule.dto.ImportFileFailureVO;
import com.pams.module.schedule.dto.NoClassScheduleCellVO;
import com.pams.module.schedule.dto.NoClassScheduleImportVO;
import com.pams.module.schedule.dto.NoClassScheduleRowVO;
import com.pams.module.schedule.generator.ClassTimetableParser;
import com.pams.module.schedule.generator.Course;
import com.pams.module.schedule.generator.NoClassScheduleExcelWriter;
import com.pams.module.schedule.generator.NoClassScheduleGenerator;
import com.pams.module.schedule.generator.NoClassScheduleMarkdownWriter;
import com.pams.module.schedule.generator.NoClassScheduleRow;
import com.pams.module.schedule.generator.PersonTimetable;
import com.pams.module.schedule.generator.SlotKey;
import com.pams.module.schedule.generator.TimetableNameExtractor;
import com.pams.repository.DepartmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 批量导入班级课表生成无课表：解析 -> 计算 -> 写 xlsx/markdown 到统一输出目录 -> 组装结果 VO。 */
@Service
public class NoClassScheduleImportService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final DepartmentRepository departmentRepository;
    private String uploadDir;

    public NoClassScheduleImportService(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    @Autowired
    public void setUploadDir(@Value("${pams.upload-dir:./uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public NoClassScheduleImportVO importTimetables(List<MultipartFile> files, Long deptId, String semester, Integer maxWeek) {
        int max = maxWeek == null || maxWeek < 1 || maxWeek > 30 ? 18 : maxWeek;
        String deptName = deptName(deptId);
        if (files == null || files.isEmpty()) throw new BizException(2702, "请至少上传一个课表文件");

        List<PersonTimetable> people = new ArrayList<>();
        List<ImportFileFailureVO> failed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (MultipartFile f : files) {
            String filename = f.getOriginalFilename() == null ? "未命名" : f.getOriginalFilename();
            String name = TimetableNameExtractor.extractName(filename);
            if (name == null) {
                failed.add(failure(filename, "无法从文件名识别姓名，请按「姓名-…」命名"));
                continue;
            }
            if (f.isEmpty()) { failed.add(failure(filename, "文件为空")); continue; }
            String lower = filename.toLowerCase();
            if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
                failed.add(failure(filename, "仅支持 .xlsx/.xls"));
                continue;
            }
            try (InputStream in = f.getInputStream()) {
                String detected = ClassTimetableParser.detectSemester(in);
                if (semester != null && !semester.isBlank() && detected != null && !semester.equals(detected)) {
                    warnings.add(filename + " 的学期(" + detected + ")与所选(" + semester + ")不一致，按所选学期输出");
                }
            } catch (Exception ignored) {
                // 学期检测失败不阻塞
            }
            try (InputStream in = f.getInputStream()) {
                Map<SlotKey, List<Course>> tt = ClassTimetableParser.parse(in);
                people.add(new PersonTimetable(name, tt));
            } catch (IllegalArgumentException e) {
                failed.add(failure(filename, e.getMessage()));
            } catch (IOException e) {
                failed.add(failure(filename, "文件解析失败"));
            }
        }

        List<NoClassScheduleRow> rows = people.isEmpty() ? List.of() : NoClassScheduleGenerator.build(people, max);
        String markdown = "";
        String downloadUrl = null;
        if (!people.isEmpty()) {
            Path dir = Path.of(uploadDir == null ? "uploads" : uploadDir, "无课表");
            try { Files.createDirectories(dir); } catch (IOException e) {
                throw new BizException(2704, "输出目录创建失败");
            }
            String safeSem = semester == null || semester.isBlank() ? "未指定学期" : semester;
            String base = "无课表_" + deptName + "_" + safeSem + "_" + LocalDateTime.now().format(STAMP);
            Path xlsxPath = dir.resolve(base + ".xlsx");
            try (java.io.OutputStream out = Files.newOutputStream(xlsxPath)) {
                NoClassScheduleExcelWriter.write(rows, deptName + " 无课表", out);
            } catch (IOException e) {
                throw new BizException(2704, "生成 Excel 失败");
            }
            markdown = NoClassScheduleMarkdownWriter.write(rows, deptName + " 无课表");
            downloadUrl = "无课表/" + xlsxPath.getFileName().toString();
        }

        return toVO(deptName, semester, rows, markdown, downloadUrl, files.size(), people.size(), failed, warnings);
    }

    /** 校验下载路径归一化后位于 uploadDir 内，返回绝对路径。 */
    public Path resolveDownload(String path) {
        Path root = Path.of(uploadDir == null ? "uploads" : uploadDir).toAbsolutePath().normalize();
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) throw new BizException(2705, "非法路径");
        return target;
    }

    private NoClassScheduleImportVO toVO(String deptName, String semester, List<NoClassScheduleRow> rows,
                                         String markdown, String downloadUrl, int total, int success,
                                         List<ImportFileFailureVO> failed, List<String> warnings) {
        NoClassScheduleImportVO vo = new NoClassScheduleImportVO();
        vo.setDeptName(deptName);
        vo.setSemester(semester);
        vo.setRows(rows.stream().map(r -> {
            NoClassScheduleRowVO rv = new NoClassScheduleRowVO();
            rv.setPeriod(r.period());
            rv.setLabel(r.label());
            rv.setHalfDay(r.halfDay());
            Map<String, List<NoClassScheduleCellVO>> days = new LinkedHashMap<>();
            r.cells().forEach((day, cells) -> days.put(String.valueOf(day),
                    cells.stream().map(c -> {
                        NoClassScheduleCellVO cv = new NoClassScheduleCellVO();
                        cv.setName(c.name());
                        cv.setFreeWeeks(c.freeWeeks());
                        return cv;
                    }).toList()));
            rv.setDays(days);
            return rv;
        }).toList());
        vo.setMarkdown(markdown);
        vo.setDownloadUrl(downloadUrl);
        vo.setTotalFiles(total);
        vo.setSuccessCount(success);
        vo.setFailed(failed);
        vo.setWarnings(warnings);
        return vo;
    }

    private String deptName(Long deptId) {
        if (deptId == null) return "未分配";
        return departmentRepository.findById(deptId).map(Department::getName).orElse("未分配");
    }

    private ImportFileFailureVO failure(String fileName, String reason) {
        ImportFileFailureVO vo = new ImportFileFailureVO();
        vo.setFileName(fileName);
        vo.setReason(reason);
        return vo;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=NoClassScheduleImportServiceTest`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/dto pams-backend/src/main/java/com/pams/module/schedule/service/NoClassScheduleImportService.java pams-backend/src/test/java/com/pams/module/schedule/NoClassScheduleImportServiceTest.java
git commit -m "feat(schedule): add NoClassScheduleImportService for batch import"
```

---

## Task 10: `CourseScheduleController` 端点 + 控制器测试

**Files:**
- Modify: `pams-backend/src/main/java/com/pams/module/schedule/controller/CourseScheduleController.java`
- Test: `pams-backend/src/test/java/com/pams/module/schedule/CourseScheduleControllerTest.java`

**Interfaces:**
- Consumes: `NoClassScheduleImportService`
- Produces:
  - `POST /api/course-schedules/import`：multipart 参数 `files`(MultipartFile[])、可选 `deptId`/`semester`/`maxWeek`；`@PreAuthorize(LEADER)`；返回 `Result<NoClassScheduleImportVO>`
  - `GET /api/course-schedules/import/download?path=<相对路径>`：返回 xlsx 附件

- [ ] **Step 1: 写失败测试**

```java
package com.pams.module.schedule;

import com.pams.module.schedule.controller.CourseScheduleController;
import com.pams.module.schedule.dto.NoClassScheduleImportVO;
import com.pams.module.schedule.service.CourseScheduleService;
import com.pams.module.schedule.service.NoClassScheduleImportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseScheduleControllerTest {

    @Test
    void importEndpointDelegatesToService() throws Exception {
        NoClassScheduleImportService importService = mock(NoClassScheduleImportService.class);
        CourseScheduleService scheduleService = mock(CourseScheduleService.class);
        CourseScheduleController ctl = new CourseScheduleController(scheduleService, importService);

        NoClassScheduleImportVO vo = new NoClassScheduleImportVO();
        vo.setSuccessCount(1);
        when(importService.importTimetables(anyList(), eq(1L), eq("2025-2026-2"), isNull())).thenReturn(vo);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(ctl).build();
        MockMultipartFile f = new MockMultipartFile("files", "张三-文件-课表.xlsx", "application/octet-stream", new byte[]{1, 2, 3});
        mvc.perform(multipart("/api/course-schedules/import")
                        .file(f)
                        .param("deptId", "1")
                        .param("semester", "2025-2026-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successCount").value(1));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd pams-backend && mvn -q test -Dtest=CourseScheduleControllerTest`
Expected: FAIL（COMPILATION ERROR：构造器签名不匹配）。

- [ ] **Step 3: 修改控制器**

在 `CourseScheduleController.java` 中：

```java
import com.pams.common.BizException;
import com.pams.module.schedule.dto.NoClassScheduleImportVO;
import com.pams.module.schedule.service.NoClassScheduleImportService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.util.List;
```

构造器改为：

```java
    private final CourseScheduleService service;
    private final NoClassScheduleImportService importService;

    public CourseScheduleController(CourseScheduleService service, NoClassScheduleImportService importService) {
        this.service = service;
        this.importService = importService;
    }
```

类内新增两个端点（放在 `analyze` 方法之后）：

```java
    // ===== 批量导入课表生成无课表 =====

    /** 批量上传班级课表 xlsx，生成无课表（含下载）。仅部长及以上。 */
    @PreAuthorize(LEADER)
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<NoClassScheduleImportVO> importTimetables(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) Integer maxWeek) {
        return Result.ok(importService.importTimetables(files == null ? List.of() : List.of(files), deptId, semester, maxWeek));
    }

    /** 下载生成的 xlsx 无课表。path 为上传目录下的相对路径。 */
    @GetMapping("/import/download")
    public ResponseEntity<Resource> download(@RequestParam String path) {
        Path target = importService.resolveDownload(path);
        if (!Files.exists(target)) throw new BizException(2705, "文件不存在");
        try {
            Resource resource = new UrlResource(target.toUri());
            String encoded = URLEncoder.encode(target.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .body(resource);
        } catch (Exception e) {
            throw new BizException(2705, "文件读取失败");
        }
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd pams-backend && mvn -q test -Dtest=CourseScheduleControllerTest`
Expected: PASS。

- [ ] **Step 5: 全量跑一遍既有测试确认无回归**

Run: `cd pams-backend && mvn -q test`
Expected: 全绿（含既有 100+ 测试）。

- [ ] **Step 6: 提交**

```bash
git add pams-backend/src/main/java/com/pams/module/schedule/controller/CourseScheduleController.java pams-backend/src/test/java/com/pams/module/schedule/CourseScheduleControllerTest.java
git commit -m "feat(schedule): add batch import and download endpoints"
```

---

## Task 11: 前端「批量导入」Tab

**Files:**
- Modify: `pams-web/src/api/courseSchedule.ts`
- Modify: `pams-web/src/pages/routine/CourseSchedule.tsx`

**Interfaces:**
- Consumes: `http`、`post`（来自 `./http`）
- Produces: `importNoClassSchedules(FormData) → Promise<NoClassScheduleImportVO>`；`downloadNoClassScheduleXlsx(downloadUrl: string) → Promise<AxiosResponse<Blob>>`

- [ ] **Step 1: 扩展 `api/courseSchedule.ts`**

在文件顶部加类型与函数：

```ts
import { get, post, put, http } from './http'
import type { AxiosResponse } from 'axios'

export interface NoClassScheduleCellVO {
  name: string
  freeWeeks: string
}
export interface NoClassScheduleRowVO {
  period: number
  label: string
  halfDay: string
  /** day "1".."5" -> 该列人员 */
  days: Record<string, NoClassScheduleCellVO[]>
}
export interface ImportFileFailureVO {
  fileName: string
  reason: string
}
export interface NoClassScheduleImportVO {
  deptName: string
  semester: string
  rows: NoClassScheduleRowVO[]
  markdown: string
  downloadUrl: string
  totalFiles: number
  successCount: number
  failed: ImportFileFailureVO[]
  warnings: string[]
}

/** 批量上传课表生成无课表（FormData: files[] + deptId + semester + maxWeek） */
export const importNoClassSchedules = (formData: FormData) =>
  post<NoClassScheduleImportVO>('/course-schedules/import', formData)

/** 下载生成的 xlsx（responseType blob，拦截器对 blob 原样返回） */
export const downloadNoClassScheduleXlsx = (downloadUrl: string) =>
  http.get('/course-schedules/import/download', {
    params: { path: downloadUrl },
    responseType: 'blob',
  }) as unknown as Promise<AxiosResponse<Blob>>
```

- [ ] **Step 2: 扩展 `CourseSchedule.tsx`**

imports 增补：

```tsx
import { Button, Empty, message, Select, Space, Spin, Tabs, Tag, Tooltip, Upload } from 'antd'
import { CalendarOutlined, CopyOutlined, DownloadOutlined, FireOutlined, UploadOutlined } from '@ant-design/icons'
import type { UploadFile } from 'antd'
import { listDepts, type DeptVO } from '@/api/dept'
import {
  analyzeFreeTime,
  getMySchedule,
  saveMySchedule,
  getScheduleConfigs,
  importNoClassSchedules,
  downloadNoClassScheduleXlsx,
  type NoClassScheduleImportVO,
  type FreeTimeAnalysisVO,
  type ScheduleConfigVO,
} from '@/api/courseSchedule'
```

组件内新增状态（`WEEKDAYS`/`SEMESTERS` 之后）：

```tsx
  const isMinister = (user?.roleLevel ?? 0) >= 3
  const [depts, setDepts] = useState<DeptVO[]>([])
  const [impDeptId, setImpDeptId] = useState<number | undefined>(user?.deptId ?? undefined)
  const [impSemester, setImpSemester] = useState('2025-2026-2')
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [importing, setImporting] = useState(false)
  const [importResult, setImportResult] = useState<NoClassScheduleImportVO | null>(null)
```

在现有 `loadConfigs` 的 `useEffect` 里追加 `listDepts`：

```tsx
  useEffect(() => {
    loadConfigs()
    listUsers({ size: 1000 })
      .then((res) => setUsers(res.records ?? []))
      .catch(() => { /* 拦截已提示 */ })
    listDepts()
      .then((res) => setDepts(res ?? []))
      .catch(() => { /* 拦截已提示 */ })
  }, [loadConfigs])
```

新增三个处理函数（放在 `exportResult` 后）：

```tsx
  const runImport = async () => {
    if (fileList.length === 0) {
      message.warning('请先选择课表文件')
      return
    }
    const formData = new FormData()
    fileList.forEach((f) => {
      if (f.originFileObj) formData.append('files', f.originFileObj, f.name)
    })
    if (impDeptId) formData.append('deptId', String(impDeptId))
    formData.append('semester', impSemester)
    setImporting(true)
    try {
      const res = await importNoClassSchedules(formData)
      setImportResult(res)
      message.success(`成功生成 ${res.successCount}/${res.totalFiles} 份课表`)
    } catch {
      /* 拦截已提示 */
    } finally {
      setImporting(false)
    }
  }

  const handleDownloadXlsx = async () => {
    if (!importResult?.downloadUrl) return
    const res = await downloadNoClassScheduleXlsx(importResult.downloadUrl)
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url
    a.download = `无课表_${importResult.deptName}_${importResult.semester}.xlsx`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const copyMarkdown = async () => {
    if (!importResult) return
    await navigator.clipboard.writeText(importResult.markdown)
    message.success('已复制 Markdown')
  }
```

新增「批量导入」Tab 内容（放在 `analyzeTab` 定义之后）：

```tsx
  const importTab = (
    <div>
      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <span style={{ fontWeight: 600 }}>批量导入课表</span>
          <Select
            placeholder="部门"
            style={{ width: 180 }}
            value={impDeptId}
            onChange={setImpDeptId}
            options={depts.map((d) => ({ value: d.id, label: d.name }))}
          />
          <Select
            value={impSemester}
            onChange={setImpSemester}
            style={{ width: 180 }}
            options={SEMESTERS.map((s) => ({ value: s, label: s }))}
          />
          <Upload
            multiple
            accept=".xlsx,.xls"
            fileList={fileList}
            beforeUpload={(file) => {
              setFileList((prev) => [...prev, file])
              return false
            }}
            onRemove={(file) => setFileList((prev) => prev.filter((f) => f.uid !== file.uid))}
          >
            <Button icon={<UploadOutlined />}>选择课表文件（可多选）</Button>
          </Upload>
          <Button type="primary" icon={<FireOutlined />} onClick={runImport} loading={importing}>
            生成无课表
          </Button>
          {importResult && (
            <>
              <Button icon={<DownloadOutlined />} onClick={handleDownloadXlsx}>
                下载 Excel
              </Button>
              <Button icon={<CopyOutlined />} onClick={copyMarkdown}>
                复制 Markdown
              </Button>
            </>
          )}
        </Space>
        {fileList.length > 0 && (
          <div style={{ marginTop: 8, color: 'var(--color-text-secondary)', fontSize: 12 }}>
            已选 {fileList.length} 个文件，文件名为「姓名-班级-班级课表.xlsx」时自动识别姓名
          </div>
        )}
      </GlassCard>

      {importResult && (
        <GlassCard style={{ padding: 16 }}>
          <div style={{ marginBottom: 12, fontSize: 13, color: 'var(--color-text-secondary)' }}>
            {importResult.deptName} · {importResult.semester} · 成功 {importResult.successCount}/{importResult.totalFiles}
            {importResult.failed.length > 0 && (
              <span style={{ color: 'var(--color-red)', marginLeft: 8 }}>
                失败 {importResult.failed.length}：
                {importResult.failed.map((f) => `${f.fileName}（${f.reason}）`).join('；')}
              </span>
            )}
          </div>
          {importResult.warnings.map((w) => (
            <div key={w} style={{ fontSize: 12, color: '#d48806', marginBottom: 4 }}>
              {w}
            </div>
          ))}
          <div style={{ overflow: 'auto' }}>
            <table style={{ borderCollapse: 'collapse', fontSize: 13, minWidth: 720 }}>
              <thead>
                <tr>
                  <th style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>节次</th>
                  {WEEKDAYS.slice(0, 5).map((d) => (
                    <th key={d} style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>
                      {d}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {importResult.rows.map((row) => (
                  <tr key={row.period}>
                    <td style={{ padding: '6px 10px', border: '1px solid var(--color-border)', whiteSpace: 'nowrap', color: 'var(--color-text-secondary)' }}>
                      {row.label}
                    </td>
                    {[1, 2, 3, 4, 5].map((day) => (
                      <td key={day} style={{ padding: '6px 10px', border: '1px solid var(--color-border)', verticalAlign: 'top', minWidth: 140 }}>
                        {(row.days[String(day)] ?? []).map((c) => (
                          <div key={c.name}>
                            {c.name}（{c.freeWeeks}）
                          </div>
                        ))}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </GlassCard>
      )}
    </div>
  )
```

Tabs items 改为（「批量导入」仅部长以上可见）：

```tsx
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'edit', label: '我的课程表', children: editTab },
          { key: 'analyze', label: '共同空闲分析', children: analyzeTab },
          ...(isMinister ? [{ key: 'import', label: '批量导入', children: importTab }] : []),
        ]}
      />
```

- [ ] **Step 3: 运行类型检查与构建**

Run: `cd pams-web && npm run build`
Expected: tsc 零错误、vite 构建通过。

- [ ] **Step 4: 提交**

```bash
git add pams-web/src/api/courseSchedule.ts pams-web/src/pages/routine/CourseSchedule.tsx
git commit -m "feat(web): add batch import tab for no-class schedule generation"
```

---

## Task 12: 全量验证与手工冒烟

**Files:** 无新增（验证性任务）

- [ ] **Step 1: 后端全量测试**

Run: `cd pams-backend && mvn -q test`
Expected: 全绿。

- [ ] **Step 2: 前端全量构建**

Run: `cd pams-web && npm run build`
Expected: 通过。

- [ ] **Step 3: 手工冒烟（浏览器）**

1. `cmd //c start.bat` 启动前后端。
2. 用部长账号（如 `wenshu` 密码 `123456`，roleLevel≥3）登录。
3. 进入 排班考勤 → 无课表制作 →「批量导入」Tab。
4. 选部门（默认当前部门）、学期（2025-2026-2）、上传 2 个真实课表（如 `张子睿-文件-2025物联网3班-班级课表.xlsx` 和 `蔡斯璇-文件-2025物联网3班-班级课表.xlsx`）。
5. 点「生成无课表」→ 预览网格出现两个姓名、周次正确；下载 Excel 打开版式正确；复制 Markdown 可用。
6. 验证边界：上传一个 `2025物联网3班.xlsx`（无姓名）→ 显示失败原因；上传 .doc → 显示"仅支持 .xlsx/.xls"。

- [ ] **Step 4: 如发现问题，回到对应任务修复并重跑该任务测试与构建**

- [ ] **Step 5: 提交剩余改动（如有）**

---

## Self-Review 结果

- **Spec 覆盖**：算法模块（WeekRangeParser/ClassTimetableParser/FreeWeekCalculator/FreeWeekFormatter/TimetableNameExtractor/Generator）→ Task 1-6；xlsx+Markdown 双输出 → Task 7-8；上传接口 + 统一输出目录 → Task 9-10；前端「批量导入」Tab → Task 11；命名用「无课表」贯穿全部类名/文案；无地点后缀（输出仅 `姓名（无课周次）`）；周一~周五 × 6 行；全集 1~18 可配置 `maxWeek`；姓名识别失败进 `failed`；.doc 不支持进 `failed`；v1 不落库（实现中无任何 repository 写入，`DepartmentRepository` 仅读部门名）。
- **占位符**：无 TBD/TODO；所有代码步骤含完整实现。
- **类型一致性**：`Course`/`SlotKey`/`PersonTimetable`/`NoClassScheduleCell`/`NoClassScheduleRow` 在 Task 1 定义、Task 5-8 消费；`WeekRangeParser.parse(String,int)`、`FreeWeekFormatter.format(Set)`、`FreeWeekCalculator.freeWeeksForSlot(List,int)`、`TimetableNameExtractor.extractName(String)`、`ClassTimetableParser.parse(InputStream)`/`detectSemester(InputStream)`、`NoClassScheduleGenerator.build(List,int)`、`NoClassScheduleExcelWriter.write(List,String,OutputStream)`、`NoClassScheduleMarkdownWriter.write(List,String)`、`NoClassScheduleImportService.importTimetables(List,Long,String,Integer)`/`resolveDownload(String)` 在 Task 9/10 消费的签名与 Task 1-8 产出一致；DTO 字段与前端 `NoClassScheduleImportVO` 类型一致。

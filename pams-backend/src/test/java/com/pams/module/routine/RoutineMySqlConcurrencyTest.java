package com.pams.module.routine;

import com.pams.common.BizException;
import com.pams.common.OrgContext;
import com.pams.module.activity.service.SigninService;
import com.pams.module.routine.dto.AttendanceStatusRequest;
import com.pams.module.routine.service.ActivityScheduleService;
import com.pams.module.routine.service.AttendanceAutoJob;
import com.pams.module.routine.service.AttendanceManageService;
import com.pams.module.routine.service.AttendanceWindow;
import com.pams.config.SqlDialect;
import com.pams.support.PostgresqlAcceptanceDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("databaseRequested")
@Timeout(35)
class RoutineMySqlConcurrencyTest {
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired SigninService signins;
    @Autowired AttendanceAutoJob settlement;
    @Autowired AttendanceManageService attendance;
    @Autowired ActivityScheduleService publication;

    static boolean databaseRequested() {
        return "1".equals(System.getenv("PAMS_ROUTINE_MYSQL_TEST"))
                || "1".equals(System.getenv("PAMS_POSTGRESQL_ACCEPTANCE"));
    }

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        if ("1".equals(System.getenv("PAMS_POSTGRESQL_ACCEPTANCE"))) {
            PostgresqlAcceptanceDatabase.register(registry);
            return;
        }
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/pams_attendance_acceptance_[0-9]{14}(?:\\?.*)?")) {
            throw new IllegalStateException("A dedicated local pams_attendance_acceptance schema is required");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/migration-mysql");
    }

    @BeforeEach
    void requireIsolatedSchema() {
        if (SqlDialect.postgresql(db)) {
            assertThat(db.queryForObject("SELECT current_database()", String.class)).startsWith("pams_acceptance_");
            return;
        }
        assertThat(db.queryForObject("SELECT DATABASE()", String.class))
                .matches("pams_attendance_acceptance_[0-9]{14}");
    }

    private record Shift(long activity, long schedule, long attendance, String token, LocalDateTime end) {}

    private Shift shift(LocalDateTime end) {
        String name = "考勤并发-" + UUID.randomUUID();
        db.update("INSERT INTO activity(name, org_id, status, deleted) VALUES (?,1,'EXECUTING',0)", name);
        long activity = db.queryForObject("SELECT id FROM activity WHERE name=?", Long.class, name);
        db.update("""
                INSERT INTO schedule(schedule_type,activity_id,schedule_date,start_time,end_time,status,org_id)
                VALUES ('ACTIVITY',?,?,?,?,'PUBLISHED',1)
                """, activity, end.toLocalDate(), java.time.LocalTime.of(0, 0, 1), end.toLocalTime());
        long schedule = db.queryForObject("SELECT id FROM schedule WHERE activity_id=?", Long.class, activity);
        db.update("INSERT INTO schedule_person(schedule_id,user_id,person_name,org_id) VALUES (?,733,'回归成员',1)", schedule);
        db.update("""
                INSERT INTO attendance(schedule_id,person_id,person_name,status,status_source,org_id)
                VALUES (?,733,'回归成员','PENDING','AUTO',1)
                """, schedule);
        long row = db.queryForObject("SELECT id FROM attendance WHERE schedule_id=?", Long.class, schedule);
        return new Shift(activity, schedule, row, signins.generateToken(activity).getToken(), end);
    }

    @Test
    void concurrentScansWriteOneSigninAndOneResult() throws Exception {
        var shift = shift(AttendanceWindow.now().plusMinutes(8));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return scan(shift); });
            var second = pool.submit(() -> { start.await(); return scan(shift); });
            start.countDown();
            assertThat(java.util.List.of(first.get(12, TimeUnit.SECONDS), second.get(12, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 2305);
        }
        assertThat(signCount(shift)).isEqualTo(1);
        assertThat(status(shift)).isEqualTo("LATE");
    }

    @Test
    void overlappingDraftsForTheSameMemberCannotBothPublish() throws Exception {
        long member = db.queryForObject("SELECT MIN(id) FROM sys_user WHERE org_id=1 AND status=1 AND deleted=0", Long.class);
        var firstFreeDate = AttendanceWindow.now().toLocalDate().plusDays(5);
        var lastFixture = db.queryForObject("SELECT MAX(schedule_date) FROM schedule WHERE org_id=1", java.time.LocalDate.class);
        var date = lastFixture != null && !lastFixture.isBefore(firstFreeDate) ? lastFixture.plusDays(1) : firstFreeDate;
        long first = draftFor(member, date);
        long second = draftFor(member, date);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return publishDraft(first); });
            var b = pool.submit(() -> { start.await(); return publishDraft(second); });
            start.countDown();
            assertThat(java.util.List.of(a.get(12, TimeUnit.SECONDS), b.get(12, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM schedule WHERE id IN (?,?) AND status='PUBLISHED'",
                Integer.class, first, second)).isEqualTo(1);
    }

    private long draftFor(long member, java.time.LocalDate date) {
        String name = "发布并发-" + UUID.randomUUID();
        db.update("INSERT INTO activity(name, org_id, status, deleted) VALUES (?,1,'EXECUTING',0)", name);
        long activity = db.queryForObject("SELECT id FROM activity WHERE name=?", Long.class, name);
        db.update("""
                INSERT INTO schedule(schedule_type,activity_id,schedule_date,start_time,end_time,status,org_id)
                VALUES ('ACTIVITY',?,?,'09:00:00','10:00:00','DRAFT',1)
                """, activity, date);
        long schedule = db.queryForObject("SELECT id FROM schedule WHERE activity_id=?", Long.class, activity);
        db.update("INSERT INTO schedule_person(schedule_id,user_id,person_name,org_id) VALUES (?,?,'回归成员',1)", schedule, member);
        return schedule;
    }

    private boolean publishDraft(long id) throws Exception {
        return inOrg(() -> {
            try {
                publication.publish(id);
                return true;
            } catch (BizException error) {
                assertThat(error).hasMessageContaining("此时段排班");
                return false;
            }
        });
    }

    @Test
    void scanSettlementAndManualCorrectionKeepOneConsistentResult() throws Exception {
        var shift = shift(AttendanceWindow.now().plusSeconds(5));
        var waiting = new CountDownLatch(1);
        var scanStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var scan = pool.submit(() -> {
                waiting.await();
                scanStarted.countDown();
                return scan(shift);
            });
            var end = pool.submit(() -> {
                waiting.await();
                while (AttendanceWindow.now().isBefore(shift.end())) Thread.sleep(30);
                settlement.run();
                return null;
            });
            var manual = pool.submit(() -> {
                waiting.await();
                while (AttendanceWindow.now().isBefore(shift.end())) Thread.sleep(30);
                return inOrg(() -> {
                    var request = new AttendanceStatusRequest();
                    request.setNewStatus("LEAVE");
                    request.setRemark("测试确认请假");
                    return attendance.updateStatus(shift.attendance(), request, 1L, "测试管理员");
                });
            });
            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                db.queryForObject("SELECT id FROM schedule WHERE id = ? FOR UPDATE", Long.class, shift.schedule());
                waiting.countDown();
                try {
                    assertThat(scanStarted.await(2, TimeUnit.SECONDS)).isTrue();
                    while (AttendanceWindow.now().isBefore(shift.end())) Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            assertThat(scan.get(12, TimeUnit.SECONDS)).isIn(200, 409, 2306);
            end.get(12, TimeUnit.SECONDS);
            manual.get(12, TimeUnit.SECONDS);
        }
        assertThat(status(shift)).isEqualTo("LEAVE");
        assertThat(signCount(shift)).isBetween(0, 1);
        assertThat(db.queryForObject("SELECT status_source FROM attendance WHERE id=?", String.class, shift.attendance()))
                .isEqualTo("MANUAL");
        assertThat(db.queryForObject("SELECT status FROM schedule WHERE id=?", String.class, shift.schedule()))
                .isEqualTo("ENDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM attendance_audit WHERE attendance_id=?", Integer.class, shift.attendance()))
                .isEqualTo(1);
    }

    @Test
    void settlementDoesNotInventAbsenceForAFormerlyUntrackedShift() {
        var shift = shift(AttendanceWindow.now().minusDays(1));
        db.update("DELETE FROM attendance WHERE id=?", shift.attendance());
        settlement.run();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM attendance WHERE schedule_id=?", Integer.class, shift.schedule()))
                .isZero();
        assertThat(db.queryForObject("SELECT status FROM schedule WHERE id=?", String.class, shift.schedule()))
                .isEqualTo("ENDED");
    }

    private int scan(Shift shift) throws Exception {
        return inOrg(() -> {
            try {
                signins.scanSignin(shift.token(), "回归成员", null, Map.of());
                return 200;
            } catch (BizException e) {
                return e.getCode();
            }
        });
    }

    private <T> T inOrg(Callable<T> task) throws Exception {
        OrgContext.set(1L);
        try {
            return task.call();
        } finally {
            OrgContext.clear();
        }
    }

    private String status(Shift shift) {
        return db.queryForObject("SELECT status FROM attendance WHERE id=?", String.class, shift.attendance());
    }

    private int signCount(Shift shift) {
        return db.queryForObject("SELECT COUNT(*) FROM signin WHERE schedule_id=?", Integer.class, shift.schedule());
    }
}

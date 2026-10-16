import { useEffect, useState } from "react";
import { Button, Empty, Select, Spin } from "antd";
import { DownloadOutlined, ReloadOutlined } from "@ant-design/icons";
import { useNavigate, useSearchParams } from "react-router-dom";
import dayjs from "dayjs";
import GlassCard from "@/components/glass/GlassCard";
import PageHeader from "@/components/glass/PageHeader";
import RoutineLayout, { RoutineNote } from "@/components/routine/RoutineLayout";
import FreeScheduleGrid from "@/components/routine/FreeScheduleGrid";
import {
  getGeneratedNoClassSchedule,
  getLatestNoClassSchedule,
  type NoClassScheduleGeneratedVO,
} from "@/api/courseSchedule";
import { listSemesters } from "@/api/activitySchedule";
import { listDepts, type DeptVO } from "@/api/dept";
import { useAuthStore } from "@/stores/auth";
import { downloadCsv, freeInWeek, WEEK_DAYS } from "@/utils/routine";

export default function FreeScheduleList() {
  const navigate = useNavigate(),
    [params] = useSearchParams(),
    user = useAuthStore((s) => s.user);
  const canManage = user?.permissions.includes("schedule:manage");
  const [depts, setDepts] = useState<DeptVO[]>([]),
    [deptId, setDeptId] = useState<number | undefined>(
      Number(params.get("deptId")) || user?.deptId || undefined,
    );
  const [semester, setSemester] = useState(params.get("semester") || ""),
    [semesterOptions, setSemesterOptions] = useState<string[]>([]),
    [week, setWeek] = useState<number>(),
    [ready, setReady] = useState(false);
  const [data, setData] = useState<NoClassScheduleGeneratedVO | null>(null),
    [loading, setLoading] = useState(false),
    [revision, setRevision] = useState(0);
  useEffect(() => {
    listDepts()
      .then((r) => setDepts(r ?? []))
      .catch(() => {});
    listSemesters()
      .then((r) => setSemesterOptions(r ?? []))
      .catch(() => {});
    getLatestNoClassSchedule()
      .then((latest) => {
        if (latest && !params.get("deptId") && !params.get("semester")) {
          setDeptId(latest.deptId);
          setSemester(latest.semester);
        }
      })
      .catch(() => {})
      .finally(() => setReady(true));
    const onFocus = () => setRevision((n) => n + 1);
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, []);
  useEffect(() => {
    let current = true;
    if (!ready) return;
    if (!deptId || !semester) {
      setData(null);
      return;
    }
    setLoading(true);
    setData(null);
    getGeneratedNoClassSchedule(deptId, semester)
      .then((r) => {
        if (current) setData(r ?? null);
      })
      .catch(() => {})
      .finally(() => {
        if (current) setLoading(false);
      });
    return () => {
      current = false;
    };
  }, [ready, deptId, semester, revision]);
  const courseUrl = `/routine/course-schedule?tab=import${deptId ? `&deptId=${deptId}` : ""}${semester ? `&semester=${encodeURIComponent(semester)}` : ""}`;
  const exportResult = () => {
    if (!data) return;
    downloadCsv(`${data.deptName}-${data.semester}-无课表`, [
      ["节次", ...WEEK_DAYS.slice(0, 5)],
      ...data.rows.map((r) => [
        r.label,
        ...[1, 2, 3, 4, 5].map((d) =>
          (r.days[String(d)] ?? [])
            .filter((c) => week === undefined || freeInWeek(c.freeWeeks, week))
            .map((c) => `${c.name}（${c.freeWeeks}）`)
            .join("、"),
        ),
      ]),
    ]);
  };
  return (
    <RoutineLayout>
      <PageHeader
        title="无课表"
        description="查看部门本学期最新无课结果，按周次核对可参与人员。"
        extra={
          canManage && (
            <Button onClick={() => navigate(courseUrl)}>重新制作无课表</Button>
          )
        }
      />
      <GlassCard>
        <div className="routine-toolbar">
          <label className="routine-filter">
            部门
            <Select
              aria-label="无课表部门"
              placeholder="请选择部门"
              value={deptId}
              onChange={setDeptId}
              options={depts.map((d) => ({ value: d.id, label: d.name }))}
            />
          </label>
          <label className="routine-filter">
            学期
            <Select
              aria-label="无课表学期"
              placeholder="请选择学期"
              value={semester || undefined}
              onChange={setSemester}
              options={[
                ...new Set([semester, ...semesterOptions].filter(Boolean)),
              ].map((s) => ({ value: s, label: s }))}
            />
          </label>
          <label className="routine-filter">
            查看周次
            <Select
              aria-label="无课表周次"
              allowClear
              placeholder="全部周次"
              value={week}
              onChange={setWeek}
              options={Array.from({ length: 30 }, (_, i) => ({
                value: i + 1,
                label: `第 ${i + 1} 周`,
              }))}
            />
          </label>
          <div className="routine-toolbar-actions">
            <Button
              icon={<ReloadOutlined />}
              onClick={() => setRevision((n) => n + 1)}
            >
              刷新
            </Button>
            {data && (
              <Button icon={<DownloadOutlined />} onClick={exportResult}>
                导出当前结果
              </Button>
            )}
            {data && canManage && (
              <Button
                type="primary"
                onClick={() =>
                  navigate(
                    `/routine/activity-schedules?semester=${encodeURIComponent(semester)}&deptId=${deptId}`,
                  )
                }
              >
                用于活动排班
              </Button>
            )}
          </div>
        </div>
        <Spin spinning={loading || !ready}>
          {data ? (
            <>
              <div className="business-section-head">
                <div>
                  <h3>{data.deptName}无课表</h3>
                  <p className="routine-muted">
                    {data.semester} /{" "}
                    {week ? `第 ${week} 周可参与人员` : "姓名后显示无课周次"}
                  </p>
                </div>
                <span className="tone-tag tone-success">最新结果</span>
              </div>
              <div className="routine-caption">
                <span>
                  最近生成：{dayjs(data.createdAt).format("YYYY-MM-DD HH:mm")}
                </span>
                <span>单 = 单数周 / 双 = 双数周</span>
              </div>
              <FreeScheduleGrid rows={data.rows} week={week} />
              <div className="routine-table-foot">
                <span>节次 × 周一至周五 / 只读结果</span>
                <span>同部门、同学期只保留最新一份</span>
              </div>
            </>
          ) : (
            <div className="routine-empty">
              <Empty
                description={
                  !deptId || !semester
                    ? "选择部门与学期查看无课结果"
                    : "这个部门尚未生成本学期无课表"
                }
              >
                {canManage && (
                  <Button type="primary" onClick={() => navigate(courseUrl)}>
                    前往批量导入
                  </Button>
                )}
              </Empty>
            </div>
          )}
        </Spin>
      </GlassCard>
      <RoutineNote>
        周次表示该成员在对应节次无课的周次。自动排班使用活动日期对应的教学周；周末请人工安排。
      </RoutineNote>
    </RoutineLayout>
  );
}

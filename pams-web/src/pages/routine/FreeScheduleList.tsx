import { useEffect, useState } from "react";
import { Alert, Button, Empty, Select, Spin } from "antd";
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
  listGeneratedSemesters,
  type NoClassScheduleGeneratedVO,
} from "@/api/courseSchedule";
import { listDepts, type DeptVO } from "@/api/dept";
import { useAuthStore } from "@/stores/auth";
import { downloadCsv, freeInWeek, WEEK_DAYS } from "@/utils/routine";

type LoadResult<T> = { key: string; data: T | null; failed?: boolean };

export default function FreeScheduleList() {
  const navigate = useNavigate(),
    [params, setParams] = useSearchParams(),
    user = useAuthStore((s) => s.user);
  const canManage = user?.permissions.includes("course:manage");
  const canGenerate = user?.permissions.includes("schedule:view") && user.permissions.includes("schedule:generate");
  const requestedDeptId = Number(params.get("deptId")) || undefined;
  const requestedSemester = params.get("semester") || "";
  const [depts, setDepts] = useState<DeptVO[]>([]);
  const [semesterOptions, setSemesterOptions] = useState<string[]>([]);
  const [week, setWeek] = useState<number>();
  const [revision, setRevision] = useState(0);
  const [latest, setLatest] = useState<LoadResult<{ deptId: number; semester: string }>>();
  const [loaded, setLoaded] = useState<LoadResult<NoClassScheduleGeneratedVO>>();
  const defaultKey = `${requestedDeptId ?? ""}:${revision}`;
  const selection = !requestedSemester && latest?.key === defaultKey ? latest : undefined;
  const deptId = requestedDeptId ?? selection?.data?.deptId ?? user?.deptId ?? undefined;
  const semester = requestedSemester || selection?.data?.semester || "";
  const queryKey = deptId && semester ? `${deptId}:${semester}:${revision}` : "";
  const response = loaded?.key === queryKey ? loaded : undefined;
  const data = response?.data;
  const loading = (!requestedSemester && !selection) || (!!queryKey && !response);
  const loadFailed = selection?.failed || response?.failed;
  useEffect(() => {
    listDepts()
      .then((r) => setDepts(r ?? []))
      .catch(() => {});
    const onFocus = () => setRevision((n) => n + 1);
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, []);
  useEffect(() => {
    let current = true;
    listGeneratedSemesters()
      .then((r) => { if (current) setSemesterOptions(r ?? []); })
      .catch(() => {});
    return () => { current = false; };
  }, [revision]);
  useEffect(() => {
    if (requestedSemester) return;
    let current = true;
    getLatestNoClassSchedule(requestedDeptId)
      .then((r) => { if (current) setLatest({ key: defaultKey, data: r ?? null }); })
      .catch(() => { if (current) setLatest({ key: defaultKey, data: null, failed: true }); });
    return () => { current = false; };
  }, [defaultKey, requestedDeptId, requestedSemester]);
  useEffect(() => {
    if (!queryKey) return;
    let current = true;
    getGeneratedNoClassSchedule(deptId, semester)
      .then((r) => {
        if (current) setLoaded({ key: queryKey, data: r ?? null });
      })
      .catch(() => {
        if (current) setLoaded({ key: queryKey, data: null, failed: true });
      });
    return () => {
      current = false;
    };
  }, [deptId, semester, queryKey]);
  const selectScope = (selectedDeptId: number | undefined, selectedSemester: string) => {
    const next = new URLSearchParams(params);
    if (selectedDeptId) next.set("deptId", String(selectedDeptId));
    if (selectedSemester) next.set("semester", selectedSemester);
    setParams(next, { replace: true });
  };
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
              onChange={(id) => selectScope(id, semester)}
              options={depts.map((d) => ({ value: d.id, label: d.name }))}
            />
          </label>
          <label className="routine-filter">
            学期
            <Select
              aria-label="无课表学期"
              placeholder="请选择学期"
              value={semester || undefined}
              onChange={(term) => selectScope(deptId, term)}
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
              aria-label="刷新无课表"
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
            {data && canGenerate && (
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
        <Spin spinning={loading}>
          {loading ? (
            <div className="routine-empty" aria-busy="true" />
          ) : loadFailed ? (
            <Alert type="error" showIcon message="无课表加载失败" description="请点击刷新重试。" />
          ) : data ? (
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

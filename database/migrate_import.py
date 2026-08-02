#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
存量材料按需迁移脚本（Task 24）。

用法：
  python database/migrate_import.py --dry-run
      # 扫描源目录，过滤 ~$ 临时文件与隐藏文件，按文件夹/文件名归类 bizType，
      # 生成 materials.csv（name,biz_type,source_path,keep）供人工筛选。

  python database/migrate_import.py --import --token <登录token> [--api http://localhost:8080]
      # 读取 materials.csv，仅导入 keep=1 的子集：先调 POST /api/files/upload 上传文件，
      # 再用返回的 file_id 调 POST /api/materials 建材料记录。

  python database/migrate_import.py --import --token <token> --limit 20
      # 仅导入前 20 行（联调用）。

人工筛选：在 materials.csv 中将需迁移的行 keep 置为 1，其余保留 0/留空。
"""
import argparse
import csv
import os
import sys

SOURCE = r"D:\StudyFiles\Office\党建办公室\信工党建办公室历届资料\信工党建第九届\年度部门材料汇总"
OUT = "materials.csv"

# 需要 requests；仅 --import 模式使用，dry-run 不依赖第三方库。
def _import_requests():
    try:
        import requests
        return requests
    except ImportError:
        sys.exit("--import 模式需要 requests 库，请先安装：py -m pip install requests")


def classify(relpath):
    """按文件夹名/文件名归类 bizType。'排班' 一律返回 SCHEDULE（V1 注释曾误拼 SCHEUDLE）。"""
    n = relpath.lower()
    if "策划" in n or "方案" in n:
        return "PLAN"
    if "签到" in n:
        return "SIGNIN"
    if any(k in n for k in ("排班", "安排", "值班")):
        return "SCHEDULE"
    if "考勤" in n:
        return "ATTENDANCE"
    if "新闻" in n:
        return "NEWS"
    if "推文" in n:
        return "ARTICLE"
    if "发票" in n:
        return "INVOICE"
    if n.endswith((".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp")):
        return "PHOTO"
    if n.endswith((".ppt", ".pptx")):
        return "PPT"
    if any(k in n for k in ("名单", "入党积极分子", "培训班")):
        return "ROSTER"
    return "OTHER"


def scan():
    """遍历源目录，过滤 ~$ 前缀与隐藏文件（点开头）。"""
    rows = []
    skipped = 0
    for root, _, files in os.walk(SOURCE):
        for f in files:
            if f.startswith("~$"):
                skipped += 1
                continue
            if f.startswith("."):
                skipped += 1
                continue
            rel = os.path.relpath(os.path.join(root, f), SOURCE)
            rows.append({
                "name": f,
                "biz_type": classify(rel),
                "source_path": os.path.join(root, f),
                "keep": "",
            })
    return rows, skipped


def write_csv(rows, path):
    with open(path, "w", newline="", encoding="utf-8-sig") as fp:
        w = csv.DictWriter(fp, fieldnames=["name", "biz_type", "source_path", "keep"])
        w.writeheader()
        w.writerows(rows)


def read_kept(path):
    """读取 CSV，返回 keep=1 的行列表；CSV 不存在时按全部行处理（无筛选兜底）。"""
    if not os.path.exists(path):
        print(f"[警告] 未找到 {path}，无任何行可导入。请先运行 --dry-run 生成清单。")
        return []
    with open(path, "r", encoding="utf-8-sig") as fp:
        rows = list(csv.DictReader(fp))
    kept = [r for r in rows if r.get("keep") == "1"]
    return kept


def do_import(rows, api, token, limit):
    requests = _import_requests()
    headers = {"Authorization": f"Bearer {token}"}
    ok = fail = 0
    for idx, r in enumerate(rows):
        if limit and idx >= limit:
            print(f"[提示] 已达 --limit {limit}，停止导入。")
            break
        src = r["source_path"]
        if not src or not os.path.isfile(src):
            print("[跳过] 源文件不存在：", src)
            continue
        try:
            with open(src, "rb") as fp:
                files = {"file": (r["name"], fp)}
                resp = requests.post(f"{api}/api/files/upload", headers=headers, files=files, timeout=60)
            if resp.status_code != 200:
                print("[上传失败]", r["name"], resp.status_code, resp.text[:120])
                fail += 1
                continue
            file_id = resp.json()["data"]["id"]
            # 后端 MaterialRequest 字段为 camelCase（bizType/activityId/deptId/fileId），
            # 不能传 snake_case（Spring 忽略未知字段 -> bizType 为 null -> @NotBlank 400）。
            data = {
                "name": r["name"],
                "bizType": r["biz_type"],
                "activityId": None,
                "deptId": None,
                "tag": "存量迁移",
                "description": "存量材料按需迁移导入",
                "fileId": file_id,
            }
            resp2 = requests.post(f"{api}/api/materials", headers=headers, json=data, timeout=30)
            if resp2.status_code == 200:
                ok += 1
                print("[OK]", r["name"], r["biz_type"])
            else:
                print("[材料创建失败]", r["name"], resp2.status_code, resp2.text[:120])
                fail += 1
        except Exception as e:  # noqa: BLE001 - 网络/IO 异常不影响整体流程
            print("[异常]", r["name"], repr(e))
            fail += 1
    print(f"导入完成：成功 {ok}，失败 {fail}")


def main():
    ap = argparse.ArgumentParser(description="存量材料按需迁移")
    ap.add_argument("--dry-run", action="store_true", help="扫描并生成 materials.csv（不调后端）")
    ap.add_argument("--import", dest="do_import", action="store_true", help="按 materials.csv keep=1 导入")
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--token", default="", help="后端登录 token")
    ap.add_argument("--limit", type=int, default=0, help="最多导入行数（联调用）")
    ap.add_argument("--source", default=SOURCE, help="覆盖源目录")
    ap.add_argument("--out", default=OUT, help="清单输出路径")
    args = ap.parse_args()

    if not args.dry_run and not args.do_import:
        ap.print_help()
        sys.exit(0)

    if args.do_import and not args.token:
        sys.exit("--import 模式需要 --token（登录 /api/auth/login 获取）")

    if args.dry_run:
        rows, skipped = scan()
        write_csv(rows, args.out)
        print(f"扫描到 {len(rows)} 个文件（已过滤 ~$ 临时文件 {skipped} 个）")
        print(f"清单写入 {args.out}，请人工将需迁移的行 keep 置为 1 后再用 --import 导入")
        from collections import Counter
        print("biz_type 分布：", dict(Counter(r["biz_type"] for r in rows)))

    if args.do_import:
        rows = read_kept(args.out)
        if not rows:
            sys.exit("materials.csv 中没有 keep=1 的行，请先人工筛选")
        do_import(rows, args.api, args.token, args.limit)


if __name__ == "__main__":
    main()

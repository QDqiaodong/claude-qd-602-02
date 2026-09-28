#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
知音琴行 · 学员课程路径 —— 端到端验证

用法：
    python3 demo/e2e_course_paths.py          # 默认 http://127.0.0.1:8362
    BASE=http://127.0.0.1:8080 python3 demo/e2e_course_paths.py

覆盖（对应需求逐条）：
  A. 三层依赖：20 → 6 → 7 → 8，学员 3 已完成 7、8 时 6/20 的结论与缺口
  B. 等价先修组：9 需 [2|13(环上)]，绕开循环选 2；组内缺口不重复计数
  C. 多路线择优：11（编号）、19（缺口最少）、28（同缺口选层级浅）、18（同层按编号）
  D. 停开课程：10 已完成仍解锁 22；4 停开未完成 → 24 受阻；23 自动绕开停开选 15
  E. 缺失关联：16 依赖已删除课程 #199 → DATA_GAP；#998 孤儿学习记录占位
  F. 旧状态：1(NORMAL)/4(NULL)/5(CANCELED)/3(LEARNING) 均不解锁（25/26/27 受阻）
  G. 循环可解释：12 自指、13⇄14、17 多级挂环，结论 CYCLE 且循环路径可读、接口稳定返回
  H. 列表与详情一致：批量接口与单课接口结论、缺口数、循环路径逐项相同
  I. 单个与批量约课入口一致：守卫结论一致；批量课程同批不互相解锁
  J. 查询规模：一次查看全部路径 SELECT 次数为固定常数（X-Query-Count），不随课程/深度增长
  K. 空记录稳定：学员 4 无任何记录，全部课程仍稳定返回；无先修课程 READY
  L. 刷新一致：连续两次拉取结论完全相同
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://127.0.0.1:8362").rstrip("/")

# 主演示学员：1 韩青梧；空记录学员：4 梁听山
SID_MAIN = 1
SID_EMPTY = 4

PASS = 0
FAIL = 0


def ok(msg):
    global PASS
    PASS += 1
    print(f"  \033[32m[PASS]\033[0m {msg}")


def bad(msg):
    global FAIL
    FAIL += 1
    print(f"  \033[31m[FAIL]\033[0m {msg}")


def title(msg):
    print(f"\n\033[33m==== {msg} ====\033[0m")


def call(method, path, payload=None):
    url = BASE + path
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode()), resp.headers
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode()), e.headers
    except Exception as e:
        return 0, {"ok": False, "message": str(e)}, {}


_bundles = {}


def bundle(student=SID_MAIN):
    if student not in _bundles:
        _, body, _ = call("GET", f"/api/students/{student}/course-paths")
        _bundles[student] = body["data"]
    return _bundles[student]


def path_row(course_no, student=SID_MAIN):
    b = bundle(student)
    return next((p for p in b["paths"] if p["courseNo"] == course_no), None)


def single_path(student, course_id):
    _, body, _ = call("GET", f"/api/students/{student}/course-paths/{course_id}")
    return body["data"]


def gap_nos(row):
    return [n["courseNo"] for n in (row.get("missingPath") or [])]


def expect_conclusion(course_no, conclusion, student=SID_MAIN, extra=""):
    row = path_row(course_no, student)
    if row is None:
        bad(f"{course_no} 未在路径列表中 {extra}")
        return row
    if row["conclusion"] == conclusion:
        ok(f"{course_no} {row['title']} → {row['conclusionName']} "
           f"({'、'.join(gap_nos(row)) or '无缺口'}) {extra}")
    else:
        bad(f"{course_no} 期望 {conclusion}，实际 {row['conclusion']} "
            f"缺口={gap_nos(row)} {extra}")
    return row


def case_seed_statuses():
    title("A/F. 种子状态：已完成解锁，旧状态 / 在学 / 取消 / 缺失均不解锁")
    # 学员 1：课 8 COMPLETED、课 10 COMPLETED(停开)、课 15 COMPLETED
    #         课 1 NORMAL、课 4 NULL(停开)、课 5 CANCELED、课 3 LEARNING
    expect_conclusion("C-6008", "READY")          # 无先修且已完成（学习状态已完成）
    expect_conclusion("C-6015", "READY")          # 无先修已完成
    expect_conclusion("C-6003", "LEARNING")       # 正在学习，无先修要求 → 正在学习
    expect_conclusion("C-6005", "READY")          # CANCELED 记录不影响，课本身可学
    expect_conclusion("C-6001", "LEARNING")       # 旧 NORMAL 记录展示为在学
    expect_conclusion("C-6025", "BLOCKED")        # 25 依赖 1，NORMAL 不解锁
    r25 = path_row("C-6025")
    if gap_nos(r25) == ["C-6001"]:
        ok("25 的最短缺口路径就是旧记录课 C-6001（未被自动视为完成）")
    else:
        bad(f"25 缺口异常：{gap_nos(r25)}")
    expect_conclusion("C-6026", "BLOCKED")        # 26 依赖 3，LEARNING 不解锁
    expect_conclusion("C-6027", "BLOCKED")        # 27 依赖 5，CANCELED 不解锁


def case_three_levels():
    title("A. 三层依赖 20 → 6 → 7 → 8")
    r20 = expect_conclusion("C-6020", "BLOCKED")
    if r20 and [n["courseNo"] for n in r20["missingPath"]] == ["C-6007", "C-6006"]:
        ok("20 缺口深→浅：C-6007 → C-6006（8 已完成，目标 20 自身不入缺口）")
    else:
        bad(f"20 缺口路径异常：{gap_nos(r20) if r20 else None}")
    r6 = path_row("C-6006")
    if r6 and r6["missingCount"] == 1 and gap_nos(r6) == ["C-6007"]:
        ok("6 只差直接先修 7（8 已完成，多级链正确折叠）")
    else:
        bad(f"6 缺口异常：{gap_nos(r6) if r6 else None}")

    # 学员 3 已完成 8、7：6 可学、20 仅差 6
    r6s3 = path_row("C-6006", student=3)
    r20s3 = path_row("C-6020", student=3)
    if r6s3 and r6s3["conclusion"] == "READY":
        ok("学员 3：6 的先修 7、8 全部完成 → 可学")
    else:
        bad(f"学员 3 的 6 结论异常：{r6s3}")
    if r20s3 and r20s3["conclusion"] == "BLOCKED" and gap_nos(r20s3) == ["C-6006"]:
        ok("学员 3：20 只差 6 一门")
    else:
        bad(f"学员 3 的 20 异常：{gap_nos(r20s3) if r20s3 else None}")


def case_elective_and_cycles():
    title("B/G. 等价先修组绕开循环 + 循环路径可读")
    # 9 需 [2 | 13]，13 与 14 成环 → 选健康成员 2
    r9 = expect_conclusion("C-6009", "BLOCKED")
    if r9 and gap_nos(r9) == ["C-6002"]:
        ok("9 自动绕开环上成员 13，缺口只计等价组中的 C-6002（不重复计数）")
    else:
        bad(f"9 缺口异常：{gap_nos(r9) if r9 else None}")

    r12 = expect_conclusion("C-6012", "CYCLE")
    if r12 and r12["cyclePath"] == ["C-6012", "C-6012"]:
        ok(f"12 自指循环路径：{' → '.join(r12['cyclePath'])}")
    else:
        bad(f"12 循环路径异常：{r12['cyclePath'] if r12 else None}")

    r13 = expect_conclusion("C-6013", "CYCLE")
    if r13 and r13["cyclePath"] == ["C-6013", "C-6014", "C-6013"]:
        ok(f"13 ⇄ 14 循环路径：{' → '.join(r13['cyclePath'])}")
    else:
        bad(f"13 循环路径异常：{r13['cyclePath'] if r13 else None}")

    r17 = expect_conclusion("C-6017", "CYCLE")
    if r17 and r17["cyclePath"] == ["C-6013", "C-6014", "C-6013"]:
        ok("17 多级依赖挂环：遍历终止在环处，循环路径仍可读，页面不被拖垮")
    else:
        bad(f"17 循环路径异常：{r17['cyclePath'] if r17 else None}")

    r14 = expect_conclusion("C-6014", "CYCLE")
    if r14 and "C-6013" in r14["cyclePath"]:
        ok("14 反向解析同样终止并给出环路径")


def case_route_selection():
    title("C. 多路线择优：缺口数 → 层级 → 编号")
    r11 = expect_conclusion("C-6011", "BLOCKED")
    if r11 and gap_nos(r11) == ["C-6002"]:
        ok("11：8 已完成，等价组 [2|3] 同缺 1 门按编号选 2")
    else:
        bad(f"11 缺口异常：{gap_nos(r11) if r11 else None}")

    r19 = expect_conclusion("C-6019", "BLOCKED")
    if r19 and gap_nos(r19) == ["C-6003"]:
        ok("19：组 [6(还缺 7+6 两门)|3(缺 1 门)]，缺失最少优先选 3")
    else:
        bad(f"19 缺口异常：{gap_nos(r19) if r19 else None}")

    r28 = expect_conclusion("C-6028", "BLOCKED")
    if r28:
        nos = gap_nos(r28)
        # 深 2 的 30/31 在前（按编号），深 1 的 29 在最后
        if nos == ["C-6030", "C-6031", "C-6029"]:
            ok("28：两条路都缺 3 门，选层级更浅的钻石两层 29；深→浅为 30/31→29")
        else:
            bad(f"28 缺口顺序异常：{nos}")

    r18 = expect_conclusion("C-6018", "BLOCKED")
    if r18 and gap_nos(r18) == ["C-6001"]:
        ok("18：组 [1|2] 同缺 1 门同层级，按课程编号选 1")
    else:
        bad(f"18 缺口异常：{gap_nos(r18) if r18 else None}")


def case_closed_courses():
    title("D. 停开课程：已完成可先修，未完成不可走")
    expect_conclusion("C-6022", "READY")            # 22 → 10(停开但已完成)
    r23 = expect_conclusion("C-6023", "READY")      # 23 组 [4 停开未完成 | 15 已完成] → 15
    if r23 and r23["missingCount"] == 0:
        ok("23 自动避开停开未完成路线，由已完成的 15 解锁")
    r24 = expect_conclusion("C-6024", "BLOCKED")
    if r24 and gap_nos(r24) == ["C-6004"]:
        node = r24["missingPath"][0]
        if node["status"] == "CLOSED":
            ok("24 的唯一先修 C-6004 已停开未完成：缺口路径保留并标注停开")
    expect_conclusion("C-6010", "CLOSED_TARGET")    # 停开课自身不能当新目标


def case_missing_and_orphans():
    title("E. 缺失关联：占位 + 数据缺口 + 孤儿学习记录")
    r16 = expect_conclusion("C-6016", "DATA_GAP")
    if r16 and r16["missingPath"]:
        node = r16["missingPath"][0]
        if node["placeholder"] and node["courseId"] == 199:
            ok(f"16 的缺口保留占位：{node['courseNo']} {node['title']}")
        else:
            bad(f"16 占位节点异常：{node}")
    else:
        bad("16 应给出占位缺口路径")
    b = bundle()
    orphans = b.get("orphanEnrollments") or []
    if any(o["courseId"] == 199 and o["placeholder"] for o in orphans):
        ok("学员 1 指向已删除课程 #199 的学习记录以占位形式保留在 orphanEnrollments")
    else:
        bad(f"孤儿学习记录缺失：{orphans}")


def case_empty_student():
    title("K. 空记录学员稳定返回，无先修课程默认可学")
    code, body, _ = call("GET", f"/api/students/{SID_EMPTY}/course-paths")
    if code != 200:
        bad(f"学员 {SID_EMPTY} 路径接口 HTTP {code}")
        return
    paths = body["data"]["paths"]
    if len(paths) >= 30:
        ok(f"学员无任何学习记录，仍稳定返回 {len(paths)} 门课程路径")
    else:
        bad(f"返回课程数异常：{len(paths)}")
    c15 = next((p for p in paths if p["courseNo"] == "C-6015"), None)
    c20 = next((p for p in paths if p["courseNo"] == "C-6020"), None)
    if c15 and c15["conclusion"] == "READY":
        ok("无先修课程对无记录学员默认可学")
    else:
        bad(f"无先修课程结论异常：{c15 and c15['conclusion']}")
    if c20 and c20["conclusion"] == "BLOCKED" and c20["missingCount"] == 3:
        ok("三层链对无记录学员给出全部 3 门缺口（深→浅）")
    else:
        bad(f"三层链缺口异常：{c20 and c20['missingCount']}")
    if not body["data"].get("orphanEnrollments"):
        ok("无孤儿记录时返回空数组")


def case_list_detail_consistency():
    title("H/L. 列表与详情逐项一致，刷新后结论不变")
    targets = ["C-6006", "C-6009", "C-6011", "C-6012", "C-6013", "C-6016",
               "C-6017", "C-6018", "C-6019", "C-6020", "C-6022", "C-6024", "C-6028"]
    b = bundle()
    for no in targets:
        row = next((p for p in b["paths"] if p["courseNo"] == no), None)
        if row is None:
            bad(f"列表缺少 {no}")
            continue
        detail = single_path(SID_MAIN, row["courseId"])
        same = (detail["conclusion"] == row["conclusion"]
                and detail["missingCount"] == row["missingCount"]
                and gap_nos(detail) == gap_nos(row)
                and (detail.get("cyclePath") or []) == (row.get("cyclePath") or []))
        if same:
            ok(f"{no} 列表/详情一致：{row['conclusion']} / 缺口 {row['missingCount']}")
        else:
            bad(f"{no} 列表={row['conclusion']},{gap_nos(row)},{row.get('cyclePath')} "
                f"详情={detail['conclusion']},{gap_nos(detail)},{detail.get('cyclePath')}")

    # 刷新一致
    _, body2, _ = call("GET", f"/api/students/{SID_MAIN}/course-paths")
    snapshot1 = [(p["courseNo"], p["conclusion"], p["missingCount"],
                  tuple(p.get("cyclePath") or [])) for p in b["paths"]]
    snapshot2 = [(p["courseNo"], p["conclusion"], p["missingCount"],
                  tuple(p.get("cyclePath") or [])) for p in body2["data"]["paths"]]
    if snapshot1 == snapshot2:
        ok("重复拉取全部路径结论完全一致（无随机漂移、无缓存旧结论）")
    else:
        bad("刷新后结论发生变化")


def case_query_count():
    title("J. 查询规模：全部路径为常数次 SELECT（X-Query-Count）")
    _, _, h_main = call("GET", f"/api/students/{SID_MAIN}/course-paths?sqlProbe=1")
    c_main = int(h_main.get("X-Query-Count", -1))
    _, _, h_empty = call("GET", f"/api/students/{SID_EMPTY}/course-paths?sqlProbe=1")
    c_empty = int(h_empty.get("X-Query-Count", -1))
    _, _, h_one = call("GET", f"/api/students/{SID_MAIN}/course-paths/20?sqlProbe=1")
    c_one = int(h_one.get("X-Query-Count", -1))
    if c_main > 0 and c_empty > 0 and c_one > 0:
        ok(f"学员1（多记录）{c_main} 次；空记录学员 {c_empty} 次；单课 20 {c_one} 次")
    else:
        bad(f"未取到查询计数：main={c_main} empty={c_empty} one={c_one}")
    # 34 门课、最深 4 层依赖：查询数必须是固定常数，不随课程数/深度线性增长。
    # 固定 4 条：课程全量 / 关系全量 / 学员名单 / 学员学习记录。
    EXPECTED = 4
    if c_main == EXPECTED and c_empty == EXPECTED:
        ok(f"全部路径查询数恒定为 {EXPECTED}（课程、关系、学员、记录各 1 条），不随课程数/深度增长")
    else:
        bad(f"查询次数不是固定 {EXPECTED}：main={c_main} empty={c_empty}")
    if c_one == c_main:
        ok(f"单课解析与全量解析查询次数相同（{c_one} 次，统一批量读取）")
    else:
        bad(f"单课 {c_one} 与全量 {c_main} 查询次数不一致")


def case_enroll_guard():
    title("I. 单个 / 批量约课共用先修守卫，同批课程不互相解锁")
    # 6 受阻（缺 7）：单个约课被拒
    c6 = next(p for p in bundle()["paths"] if p["courseNo"] == "C-6006")
    code, body, _ = call("POST", "/api/enrollments",
                         {"studentId": SID_MAIN, "courseId": c6["courseId"], "lessons": 1})
    if code == 400 and "最短先修路径" in body["message"] and "C-6007" in body["message"]:
        ok(f"单个约课被同一守卫拦截：{body['message'][:60]}…")
    else:
        bad(f"单个约课 6 应被先修守卫拦截：{code} {body.get('message')}")

    # 批量 [32, 33, 34]：32 需 33、33 需 34，学员 1 在三门课上都没有记录。
    # 即使三门都在本次提交里，守卫只读库里既有 COMPLETED 记录，同批不能互为已完成先修。
    ids = {}
    for p in bundle()["paths"]:
        ids[p["courseNo"]] = p["courseId"]
    code, body, _ = call("POST", "/api/enrollments/batch",
                         {"studentId": SID_MAIN,
                          "courseIds": [ids["C-6032"], ids["C-6033"], ids["C-6034"]],
                          "lessons": 1})
    if code == 400 and ("循环" in body["message"] or "先修" in body["message"]
                        or "缺口" in body["message"]):
        ok(f"批量互为先修被拦，同批未互相解锁：{body['message'][:70]}…")
    else:
        bad(f"批量 [32,33,34] 应被先修守卫拦截：{code} {body.get('message')}")

    # 单个 / 批量对同一受阻课给出同一结论（批量只放一门 6）
    code_b, body_b, _ = call("POST", "/api/enrollments/batch",
                             {"studentId": SID_MAIN, "courseIds": [ids["C-6006"]], "lessons": 1})
    if code_b == 400 and body_b["message"] == body["message"]:
        ok("单个与批量入口对 C-6006 的拒绝结论完全一致")
    else:
        bad(f"两入口结论不一致：single={body.get('message')} batch={body_b.get('message')}")

    # 循环课程约课：明确报循环路径，而不是栈错误
    code, body, _ = call("POST", "/api/enrollments",
                         {"studentId": SID_MAIN, "courseId": ids["C-6013"], "lessons": 1})
    if code == 400 and "循环" in body["message"] and "C-6013" in body["message"]:
        ok(f"循环课程约课返回可读错误：{body['message'][:60]}…")
    else:
        bad(f"循环课程约课应 400 且可读：{code} {body.get('message')}")


def case_ready_enroll_still_open():
    title("附. 可学课程守卫放行（后续余额/满员等闸口由原有规则处理）")
    # 学员 1 约 22（先修 10 已完成）—— 学员 1 余额 12 足够
    c22 = next(p for p in bundle()["paths"] if p["courseNo"] == "C-6022")
    code, body, _ = call("POST", "/api/enrollments",
                         {"studentId": SID_MAIN, "courseId": c22["courseId"], "lessons": 1})
    if code == 200:
        ok("先修满足的 22 单个约课通过（守卫放行，不被先修误拦）")
    else:
        bad(f"22 应可约：{code} {body.get('message')}")
    # 重复执行脚本时 22 已有在学记录 → 重复约课拦截也属预期
    if code == 400 and ("已经约过" in body["message"]):
        ok("重复运行：22 已在学，被重复约课规则拦下（先修仍为满足）")


def main():
    print(f"目标服务：{BASE}")
    try:
        code, _, _ = call("GET", "/api/courses")
        if code != 200:
            raise RuntimeError(f"HTTP {code}")
    except Exception as e:
        print(f"\033[31m后端不可达（{e}），请先启动服务。\033[0m")
        sys.exit(2)

    case_seed_statuses()
    case_three_levels()
    case_elective_and_cycles()
    case_route_selection()
    case_closed_courses()
    case_missing_and_orphans()
    case_empty_student()
    case_list_detail_consistency()
    case_query_count()
    case_enroll_guard()
    case_ready_enroll_still_open()

    print("\n" + "=" * 60)
    print(f"\033[32m通过 {PASS}\033[0m，\033[31m失败 {FAIL}\033[0m")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()

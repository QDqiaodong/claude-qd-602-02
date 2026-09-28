#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
知音琴行 · 学员课程路径 —— 端到端验证（只读 + 可重复执行）

用法：
    python3 demo/e2e_course_paths.py
    BASE=http://127.0.0.1:8080 python3 demo/e2e_course_paths.py

覆盖：
  A. 列表 / 详情 / 单查 / 批量路径 四入口结论完全一致（verdict / 缺口数 / 缺口路径 / 循环路径）。
  B. 状态口径：只有 COMPLETED 解锁；在学 NORMAL / 已取消 CANCELED / 历史 NULL 状态缺失都受阻。
  C. 三层依赖：学员1 对 C-6104 的最短缺口路径为 C-6104（前三层已完成）；
     等价先修组：C-6114 组内单门完成即满足、缺口不重复计数。
  D. 多路线择优：C-6118 缺失最少；C-6119 同缺失数层级最浅；C-6120 层级同按课程编号。
  E. 停开课：学员1 历史已完成 C-6004 → C-6112 可学；学员2 在学 C-6004 → C-6112 受阻。
  F. 脏数据：C-6122 先修指向已删除课程 → 占位 + 数据缺口；约课记录关联缺失仍展示占位。
  G. 环路：C-6123 自指、C-6124/C-6125 两环、C-6126 深链遇环，均返回可读循环路径、HTTP 200。
  H. 查询规模：某学员全部路径与列表带路径 SELECT 次数为固定常数（X-Query-Count），
     不随课程数量、依赖深度线性增长；空记录学员也稳定。
  I. 约课闸门：单个与批量入口对同一门先修不足课程返回一致口径的中文拒绝；
     批量同选「先修+后续」两门，后续课不能把同批的先修课当已完成。
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BASE", "http://127.0.0.1:8362").rstrip("/")

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
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode()), resp.headers
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode()), e.headers
    except Exception as e:
        return 0, {"ok": False, "message": str(e)}, {}


def paths(student_id):
    _, body, _ = call("GET", f"/api/courses/paths?studentId={student_id}")
    return {p["courseNo"]: p for p in body["data"]}


def gap_id_chain(view):
    return [g["courseId"] for g in (view.get("gapPath") or [])]


def gap_no_chain(view):
    return [("已删除课程#" + str(g["courseId"])) if g.get("missing") else g["courseNo"]
            for g in (view.get("gapPath") or [])]


# ---------------- A. 四入口结论一致 ----------------

def case_a_entry_consistency():
    title("A. 列表 / 详情 / 单查 / 批量路径 结论完全一致")
    for student_id in (1, 2, 3, 4):
        batch = paths(student_id)
        _, list_body, _ = call("GET", f"/api/courses?studentId={student_id}")
        list_map = {c["courseNo"]: c for c in list_body["data"]}
        mismatch = 0
        for no, bp in batch.items():
            row = list_map.get(no)
            if row is None or row.get("path") is None:
                bad(f"学员{student_id} 课程 {no} 列表未内嵌路径")
                mismatch += 1
                continue
            lp = row["path"]
            cid = bp["courseId"]
            _, detail_body, _ = call("GET", f"/api/courses/{cid}/detail?studentId={student_id}")
            _, single_body, _ = call("GET", f"/api/courses/{cid}/path?studentId={student_id}")
            dp = detail_body["data"].get("path")
            sp = single_body["data"]
            for field in ("verdict", "missingCount", "missingLevel", "cyclePath",
                          "learningState", "closed", "dataGap", "summary"):
                if not (bp.get(field) == lp.get(field) == (dp or {}).get(field) == sp.get(field)):
                    bad(f"学员{student_id} {no} 字段 {field} 四入口不一致："
                        f"批量={bp.get(field)} 列表={lp.get(field)} 详情={(dp or {}).get(field)} 单查={sp.get(field)}")
                    mismatch += 1
            if gap_id_chain(bp) != gap_id_chain(lp) or gap_id_chain(bp) != gap_id_chain(sp) \
                    or gap_id_chain(bp) != gap_id_chain(dp or {}):
                bad(f"学员{student_id} {no} 缺口路径四入口不一致："
                    f"{gap_no_chain(bp)} vs {gap_no_chain(lp)} vs {gap_no_chain(sp)}")
                mismatch += 1
        if mismatch == 0:
            ok(f"学员{student_id}：全部 {len(batch)} 门课四入口 verdict/缺口/循环路径逐项一致")

    # 刷新后结论稳定：连取两次完全相等
    first = paths(1)
    second = paths(1)
    drift = [no for no in first
             if json.dumps(first[no], sort_keys=True, ensure_ascii=False)
             != json.dumps(second[no], sort_keys=True, ensure_ascii=False)]
    if not drift:
        ok("连续两次批量解析结论完全一致（无漂移、无旧缓存）")
    else:
        bad(f"刷新后结论发生漂移：{drift}")


# ---------------- B. 学习状态口径 ----------------

def case_b_status_semantics():
    title("B. 只有已完成能解锁：在学/已取消/状态缺失均受阻")
    p1, p2, p3 = paths(1), paths(2), paths(3)

    # 学员1：C-6101 已完成
    if p1["C-6101"]["learningState"] == "COMPLETED" and p1["C-6101"]["verdict"] == "AVAILABLE":
        ok("已完成记录：C-6101 状态=已完成")
    else:
        bad(f"C-6101 状态异常：{p1['C-6101']['learningState']}/{p1['C-6101']['verdict']}")

    # 学员2：C-6102 正在学习 —— 作为 C-6103 的先修不满足
    if p2["C-6102"]["learningState"] == "NORMAL":
        ok("正在学习：学员2 C-6102 状态=NORMAL")
    else:
        bad(f"C-6102 应为 NORMAL：{p2['C-6102']['learningState']}")
    if p2["C-6103"]["verdict"] == "BLOCKED":
        ok("正在学习不解锁：学员2 的 C-6103 受阻")
    else:
        bad(f"C-6103 应受阻：{p2['C-6103']['verdict']}")

    # 学员3：C-6101 已取消 —— C-6102 受阻
    if p3["C-6101"]["learningState"] == "CANCELED":
        ok("已取消：学员3 C-6101 状态=CANCELED，且 C-6102 受阻："
           + p3["C-6102"]["verdict"])
    else:
        bad(f"C-6101 应为 CANCELED：{p3['C-6101']['learningState']}")
    if p3["C-6102"]["verdict"] == "BLOCKED":
        ok("已取消不解锁：学员3 的 C-6102 受阻")
    else:
        bad(f"C-6102 应受阻：{p3['C-6102']['verdict']}")

    # 学员2：C-6113 历史 status=NULL —— 展示为状态缺失，且不能解锁 C-6114
    item = next(it for g in p2["C-6114"]["groups"] for it in g["items"]
                if it["course"]["courseNo"] == "C-6113")
    if item["learningState"] == "LEGACY_MISSING" and item["learningStateName"] == "状态缺失":
        ok("历史 NULL 状态：C-6113 展示为「状态缺失」")
    else:
        bad(f"C-6113 应展示状态缺失：{item['learningState']}/{item['learningStateName']}")
    if p2["C-6114"]["verdict"] == "BLOCKED":
        ok("状态缺失不自动视为已完成：学员2 的 C-6114 受阻")
    else:
        bad(f"C-6114 应受阻：{p2['C-6114']['verdict']}")

    # 学员4：没有任何课程记录，无先修课默认可学
    p4 = paths(4)
    if p4["C-6101"]["verdict"] == "AVAILABLE" and p4["C-6101"]["learningState"] == "NONE":
        ok("空记录学员：无先修课程默认可学、状态=未学习")
    else:
        bad(f"空记录学员 C-6101 异常：{p4['C-6101']['verdict']}/{p4['C-6101']['learningState']}")


# ---------------- C. 三层依赖 + 等价先修组 ----------------

def case_c_chain_and_any_group():
    title("C. 三层依赖与等价先修组")
    p1, p2, p3 = paths(1), paths(2), paths(3)

    # 学员1 完成 C-6101/2/3：C-6104 可学
    if p1["C-6104"]["verdict"] == "AVAILABLE":
        ok("三层主链：学员1 已走通 C-6101→C-6102→C-6103，C-6104 可学")
    else:
        bad(f"C-6104 应可学：{p1['C-6104']['summary']}")

    # 学员2 完成 C-6101，C-6102 在学：C-6104 缺口链 = C-6102 → C-6103 → C-6104，缺 2 门
    chain = gap_no_chain(p2["C-6104"])
    if chain == ["C-6102", "C-6103", "C-6104"] and p2["C-6104"]["missingCount"] == 2:
        ok(f"多级缺口路径：学员2 C-6104 缺口 {' → '.join(chain)}（缺 2 门，不含已完成的 C-6101）")
    else:
        bad(f"C-6104 缺口路径错误：{chain} / 缺 {p2['C-6104']['missingCount']}")

    # 等价先修组 C-6114 <- ANY(C-6104, C-6113)：学员1 C-6104 已完成（传递）→ 可学
    if p1["C-6114"]["verdict"] == "AVAILABLE":
        ok("等价先修组：学员1 完成组内 C-6104（多级解锁），C-6114 可学，不要求两门都完成")
    else:
        bad(f"C-6114 应可学：{p1['C-6114']['summary']}")

    # 学员3 完成 C-6115：C-6117 <- ANY(C-6115,C-6116) 可学；且缺口不重复计数
    if p3["C-6117"]["verdict"] == "AVAILABLE":
        ok("等价先修组：学员3 完成 C-6115 即满足 C-6117")
    else:
        bad(f"C-6117 应可学：{p3['C-6117']['summary']}")
    p4 = paths(4)
    if p4["C-6117"]["missingCount"] == 1 and gap_no_chain(p4["C-6117"]) == ["C-6115", "C-6117"]:
        ok("等价组不重复计数：无记录学员 C-6117 只缺 1 门（取编号最小的 C-6115），不是两门叠加")
    else:
        bad(f"C-6117 缺口应=1 且选 C-6115：{p4['C-6117']['missingCount']} / {gap_no_chain(p4['C-6117'])}")


# ---------------- D. 多路线择优 ----------------

def case_d_route_selection():
    title("D. 多路线择优：缺失最少 → 层级最浅 → 课程编号")
    p1, p2, p4 = paths(1), paths(2), paths(4)

    # C-6118 <- ANY(C-6103[学员1已完成], C-6109)：学员1 可学（多门先修短链走通）
    if p1["C-6118"]["verdict"] == "AVAILABLE":
        ok("走通多门先修短链：学员1 的 C-6103 已完成，C-6118 可学")
    else:
        bad(f"C-6118 对学员1应可学：{p1['C-6118']['summary']}")

    # 学员2：C-6103 路线缺 C-6102/C-6103 两门；C-6109 路线缺 1 门 → 选缺失少的
    chain2 = gap_no_chain(p2["C-6118"])
    if p2["C-6118"]["missingCount"] == 1 and chain2 == ["C-6109", "C-6118"]:
        ok(f"缺失最少：学员2 C-6118 选只缺 1 门的 C-6109 路线：{' → '.join(chain2)}")
    else:
        bad(f"学员2 C-6118 路线错误：缺 {p2['C-6118']['missingCount']} / {chain2}")

    # C-6119 <- ANY(C-6104[学员2 缺3], C-6128[<-6127, 缺2])：选缺 2 的
    chain19 = gap_no_chain(p2["C-6119"])
    if p2["C-6119"]["missingCount"] == 2 and chain19 == ["C-6127", "C-6128", "C-6119"]:
        ok(f"缺失最少（深链对比）：学员2 C-6119 选 C-6128 链（缺2）：{' → '.join(chain19)}")
    else:
        bad(f"学员2 C-6119 路线错误：缺 {p2['C-6119']['missingCount']} / {chain19}")

    # C-6129 <- ANY(C-6102[学员2 缺1·层级1], C-6128[缺2])：
    # 学员2 两条路线缺失数不同，仍选缺失少的 C-6102；无记录学员两条都缺 1 门时比层级
    chain29 = gap_no_chain(p2["C-6129"])
    if p2["C-6129"]["missingCount"] == 1 and chain29 == ["C-6102", "C-6129"]:
        ok(f"缺失最少：学员2 C-6129 选 C-6102（缺1）：{' → '.join(chain29)}")
    else:
        bad(f"学员2 C-6129 路线错误：缺 {p2['C-6129']['missingCount']} / {chain29}")

    # 无记录学员：C-6102 链缺1门、层级1（6102<-6101 不算，因为 C-6102 要求6101，共缺2门）；
    # 这里真正同缺失数对比：C-6129 <- C-6102 链缺 {6101,6102}=2 门/层级2；
    #                        <- C-6128 链缺 {6127,6128}=2 门/层级2 → 编号 C-6102 胜
    chain29b = gap_no_chain(p4["C-6129"])
    if p4["C-6129"]["missingCount"] == 2 and chain29b == ["C-6101", "C-6102", "C-6129"]:
        ok(f"同缺失数同层级按编号：无记录学员 C-6129 选 C-6102 链：{' → '.join(chain29b)}")
    else:
        bad(f"无记录学员 C-6129 排序错误：缺 {p4['C-6129']['missingCount']} / {chain29b}")

    # 层级最浅：用 C-6120 已演示编号排序；这里再单独验证层级维度 ——
    # 学员2 已完成 C-6101：C-6102 路线缺 1 门且层级 1（直接先修），
    # C-6128 路线缺 2 门且层级 2 —— 缺失数与层级都更优的 C-6102 已在上面选中。
    if p2["C-6129"]["missingLevel"] == 1:
        ok(f"层级最浅：学员2 C-6129 选中路线缺口层级=1（直接先修层）")
    else:
        bad(f"C-6129 缺口层级应为 1：{p2['C-6129']['missingLevel']}")

    # C-6120 <- ANY(C-6109, C-6115)：无记录学员两个入口都缺 1 门、层级都 1 → 编号 C-6109
    chain20 = gap_no_chain(p4["C-6120"])
    if p4["C-6120"]["missingCount"] == 1 and chain20 == ["C-6109", "C-6120"]:
        ok(f"编号兜底：C-6120 同缺失同层级选课程编号更小的 C-6109：{' → '.join(chain20)}")
    else:
        bad(f"C-6120 排序兜底错误：缺 {p4['C-6120']['missingCount']} / {chain20}")

    # 同缺失数、不同层级（扇出树 vs 线性链）：无记录学员 C-6136
    #   线性链 C-6134 缺 {6131,6132,6133,6134}=4 门、层级 4
    #   扇出树 C-6135 缺 {6137,6138,6139,6135}=4 门、层级 3 → 层级浅的扇出树胜
    chain36 = gap_no_chain(p4["C-6136"])
    if p4["C-6136"]["missingCount"] == 4 and p4["C-6136"]["missingLevel"] == 3:
        ok(f"同缺失数选层级最浅（扇出树深度3 vs 线性链深度4）：缺4门选深度3路线")
    else:
        bad(f"C-6136 层级择优错误：缺 {p4['C-6136']['missingCount']} / 层级 {p4['C-6136']['missingLevel']}")
    if "C-6135" in chain36 and "C-6134" not in chain36:
        ok(f"选中的缺口路径为扇出树：{' → '.join(chain36)}")
    else:
        bad(f"C-6136 应选扇出树路线：{chain36}")


# ---------------- E. 停开课 ----------------

def case_e_closed_course():
    title("E. 停开课：历史已完成仍解锁；未完成不可作新目标")
    p1, p2 = paths(1), paths(2)

    if p1["C-6112"]["verdict"] == "AVAILABLE":
        ok("学员1 历史已完成停开课 C-6004 → C-6112 可学（停开课仍作过去先修）")
    else:
        bad(f"C-6112 对学员1应可学：{p1['C-6112']['summary']}")

    if p2["C-6112"]["verdict"] == "BLOCKED" and not p2["C-6112"]["dataGap"]:
        chain = gap_no_chain(p2["C-6112"])
        ok(f"学员2 在学 C-6004（未完成）→ C-6112 受阻且非数据缺口：{' → '.join(chain)}")
    else:
        bad(f"C-6112 对学员2应受阻非数据缺口：{p2['C-6112']['verdict']}/{p2['C-6112']['dataGap']}")


# ---------------- F. 关联缺失 ----------------

def case_f_missing_relations():
    title("F. 脏数据：关联对象缺失保留占位并标数据缺口")
    p1 = paths(1)
    v = p1["C-6122"]
    if v["verdict"] == "BLOCKED" and v["dataGap"]:
        ok("C-6122 先修课程已删除：受阻 + 数据缺口")
    else:
        bad(f"C-6122 应受阻且数据缺口：{v['verdict']}/{v['dataGap']}")
    first_gap = (v.get("gapPath") or [{}])[0]
    if first_gap.get("missing") and first_gap.get("courseId") == 999001:
        ok("缺口路径保留占位：已删除课程#999001")
    else:
        bad(f"缺口占位错误：{first_gap}")
    item = v["groups"][0]["items"][0]
    if item["course"]["missing"] and item["dataGap"]:
        ok("先修项以占位形式展示（id 保留、名称为空、标缺失）")
    else:
        bad(f"先修项占位错误：{item['course']}")

    # 约课记录列表：已删除课程/学员的记录仍展示占位，不拖垮整页
    _, body, _ = call("GET", "/api/enrollments")
    rows = body["data"]
    orphan_course = next((r for r in rows if r["courseId"] == 999002), None)
    if orphan_course is not None and not orphan_course.get("courseTitle"):
        ok("约课记录关联课程缺失：记录保留、课程名为空（前端展示占位）")
    else:
        bad("缺少指向已删除课程 999002 的约课占位记录")


# ---------------- G. 环路 ----------------

def case_g_cycles():
    title("G. 自指 / 两环 / 深链遇环：可读终止")
    p1 = paths(1)

    if p1["C-6123"]["verdict"] == "CYCLE" and p1["C-6123"]["cyclePath"] == "C-6123 → C-6123":
        ok("自指环路：C-6123 → C-6123，解析终止、接口 200")
    else:
        bad(f"自指环路径错误：{p1['C-6123']['verdict']}/{p1['C-6123']['cyclePath']}")

    cp = p1["C-6124"]["cyclePath"]
    if p1["C-6124"]["verdict"] == "CYCLE" and cp == "C-6124 → C-6125 → C-6124":
        ok(f"两环可读路径：{cp}")
    else:
        bad(f"两环路径错误：{p1['C-6124']['verdict']}/{cp}")

    deep = p1["C-6126"]
    if deep["verdict"] == "CYCLE" and "C-6125" in (deep["cyclePath"] or ""):
        ok(f"深链遇环终止（不无限递归）：{deep['cyclePath']}")
    else:
        bad(f"深链环路错误：{deep['verdict']}/{deep.get('cyclePath')}")

    # 同页其它课程不受环影响：C-6101 依然可学
    if p1["C-6101"]["verdict"] == "AVAILABLE":
        ok("环路数据不拖垮整页：无关课程 C-6101 结论正常")
    else:
        bad("环路污染了无关课程的结论")


# ---------------- H. 查询规模 ----------------

def case_h_query_count():
    title("H. 查询规模：全部路径 SELECT 次数为固定常数")
    _, _, h_full = call("GET", "/api/courses/paths?studentId=1&sqlProbe=1")
    c_full = int(h_full.get("X-Query-Count", -1))
    _, _, h_empty = call("GET", "/api/courses/paths?studentId=4&sqlProbe=1")
    c_empty = int(h_empty.get("X-Query-Count", -1))
    _, _, h_list = call("GET", "/api/courses?studentId=2&sqlProbe=1")
    c_list = int(h_list.get("X-Query-Count", -1))
    _, _, h_single = call("GET", "/api/courses/27/path?studentId=1&sqlProbe=1")
    c_single = int(h_single.get("X-Query-Count", -1))

    print(f"     全部路径={c_full} 空记录={c_empty} 列表带路径={c_list} 单门路径={c_single} 次 SELECT")
    # 全部路径：课程 + 组 + 项 + 约课 = 4 条；空记录学员同样 4 条
    if c_full == 4 and c_empty == 4:
        ok("全部课程路径恒定 4 条 SELECT，不随课程数量/依赖深度/记录数增长")
    else:
        bad(f"全部路径查询数应为 4：full={c_full} empty={c_empty}")
    # 列表带路径复用同一批量解析
    if c_list == 4:
        ok("课程列表带学员路径同样恒定 4 条 SELECT")
    else:
        bad(f"列表带路径查询数应为 4：{c_list}")
    # 单门路径也要全量关系图（不能只查直接上一层）：同样 4 条
    if c_single == 4:
        ok("单门路径同样全量批量读取 4 条（深层/环路口径一致）")
    else:
        bad(f"单门路径查询数应为 4：{c_single}")


# ---------------- I. 约课闸门：单个 == 批量，同批不互解锁 ----------------

def case_i_enroll_gate():
    title("I. 约课闸门：单个/批量同口径，同批课程不互当已完成先修")

    def expect_blocked(code, body, where):
        msg = body.get("message", "")
        if code == 400 and "先修" in msg:
            ok(f"{where} 被同一闸门拒绝：{msg}")
        else:
            bad(f"{where} 应 400 且提示先修，实际 {code}：{msg}")

    # 学员2 约 C-6103（先修 C-6102 仅在学）：单个入口
    code, body, _ = call("POST", "/api/enrollments", {
        "studentId": 2, "courseId": 8, "lessons": 1})
    expect_blocked(code, body, "单个约课 C-6103")

    # 批量入口同样拒绝同一门课（与单个提示同口径，都来自统一解析）
    code, body, _ = call("POST", "/api/enrollments/batch", {
        "studentId": 2, "courseIds": [8], "lessons": 1})
    expect_blocked(code, body, "批量约课 C-6103")

    # 同批先选 C-6102 再选 C-6103：学员4 两门都无记录，C-6103 不能把同批的 C-6102 当已完成
    code, body, _ = call("POST", "/api/enrollments/batch", {
        "studentId": 4, "courseIds": [7, 8], "lessons": 1})
    msg = body.get("message", "")
    if code == 400 and "先修" in msg and "C-6102" in msg:
        ok(f"同批不互解锁：[C-6102, C-6103] 被拒，缺口仍含 C-6102：{msg}")
    else:
        bad(f"同批互充当先修未拦住：{code} {msg}")

    # 反向验证：先修已完成的学员1 约 C-6104 不会被先修闸拦（可能撞满员/余额以外的错误，
    # 但不能是先修提示）。用 C-6118（短链已完成）做只读安全校验：直接查路径=可学即可
    p1 = paths(1)
    if p1["C-6118"]["verdict"] == "AVAILABLE":
        ok("反向对照：先修齐全的课程路径结论为可学，闸门不会误拦")
    else:
        bad(f"C-6118 应可学：{p1['C-6118']['summary']}")


def main():
    print(f"目标服务：{BASE}")
    try:
        code, body, _ = call("GET", "/api/courses")
        if code != 200:
            raise RuntimeError(f"HTTP {code}")
        if len(body["data"]) < 20:
            print(f"\033[31m课程数量仅 {len(body['data'])}，请先初始化含路径模块的新种子数据。\033[0m")
            sys.exit(2)
    except Exception as e:
        print(f"\033[31m后端不可达（{e}），请先启动服务。\033[0m")
        sys.exit(2)

    case_a_entry_consistency()
    case_b_status_semantics()
    case_c_chain_and_any_group()
    case_d_route_selection()
    case_e_closed_course()
    case_f_missing_relations()
    case_g_cycles()
    case_h_query_count()
    case_i_enroll_gate()

    print("\n" + "=" * 60)
    print(f"\033[32m通过 {PASS}\033[0m，\033[31m失败 {FAIL}\033[0m")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()

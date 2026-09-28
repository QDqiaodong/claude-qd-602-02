package com.music.instrument.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.music.instrument.common.BizException;
import com.music.instrument.domain.CoursePrereqGroup;
import com.music.instrument.domain.CoursePrerequisite;
import com.music.instrument.domain.Enrollment;
import com.music.instrument.domain.LessonCourse;
import com.music.instrument.dto.view.CoursePathView;
import com.music.instrument.dto.view.GapCourse;
import com.music.instrument.mapper.CoursePrereqGroupMapper;
import com.music.instrument.mapper.CoursePrerequisiteMapper;
import com.music.instrument.mapper.EnrollmentMapper;
import com.music.instrument.mapper.LessonCourseMapper;

/**
 * 课程路径解析器单测：三层依赖 / 等价先修组 / 多路线择优（缺失数→层级→编号）/
 * 停开课历史先修 / 关联缺失占位 / 旧状态不解锁 / 自指与两环可读终止 / 空记录稳定。
 */
@ExtendWith(MockitoExtension.class)
class CoursePathServiceTest {

    @Mock
    private LessonCourseMapper lessonCourseMapper;
    @Mock
    private CoursePrereqGroupMapper prereqGroupMapper;
    @Mock
    private CoursePrerequisiteMapper prerequisiteMapper;
    @Mock
    private EnrollmentMapper enrollmentMapper;

    private static final LocalDateTime T = LocalDateTime.parse("2026-02-01T09:00:00");

    private LessonCourse course(long id, String no, String status) {
        LessonCourse c = new LessonCourse();
        c.setId(id);
        c.setCourseNo(no);
        c.setTitle("课程" + no);
        c.setStatus(status);
        c.setCapacity(10);
        c.setEnrolledCount(0);
        c.setCreatedAt(T);
        return c;
    }

    private CoursePrereqGroup group(long id, long courseId, int no, String mode) {
        CoursePrereqGroup g = new CoursePrereqGroup();
        g.setId(id);
        g.setCourseId(courseId);
        g.setGroupNo(no);
        g.setMatchMode(mode);
        g.setCreatedAt(T);
        return g;
    }

    private CoursePrerequisite prereq(long id, long groupId, long courseId) {
        CoursePrerequisite p = new CoursePrerequisite();
        p.setId(id);
        p.setGroupId(groupId);
        p.setPrerequisiteId(courseId);
        p.setCreatedAt(T);
        return p;
    }

    private Enrollment enrollment(long studentId, long courseId, String status) {
        Enrollment e = new Enrollment();
        e.setStudentId(studentId);
        e.setCourseId(courseId);
        e.setStatus(status);
        return e;
    }

    private CoursePathService serviceWith(List<LessonCourse> courses,
                                          List<CoursePrereqGroup> groups,
                                          List<CoursePrerequisite> prerequisites,
                                          List<Enrollment> enrollments) {
        when(lessonCourseMapper.findAll()).thenReturn(courses);
        when(prereqGroupMapper.findAll()).thenReturn(groups);
        when(prerequisiteMapper.findAll()).thenReturn(prerequisites);
        when(enrollmentMapper.listPlainByStudentId(any())).thenReturn(enrollments);
        return new CoursePathService(lessonCourseMapper, prereqGroupMapper,
                prerequisiteMapper, enrollmentMapper);
    }

    private Map<Long, CoursePathView> resolveAll(CoursePathService service, long studentId,
                                                 List<LessonCourse> courses) {
        Map<Long, CoursePathView> map = new LinkedHashMap<>();
        for (LessonCourse c : courses) {
            map.put(c.getId(), service.resolve(studentId, c.getId()));
        }
        return map;
    }

    private List<Long> gapIds(CoursePathView view) {
        return view.getGapPath().stream().map(g -> g.getCourseId()).toList();
    }

    // ---------- 基础：无先修默认可学；四种状态只有 COMPLETED 解锁 ----------

    @Test
    void noPrerequisite_isAvailable() {
        List<LessonCourse> courses = List.of(course(1, "C-1", "OPEN"));
        CoursePathService service = serviceWith(courses, List.of(), List.of(), List.of());
        CoursePathView view = service.resolve(1L, 1L);
        assertEquals("AVAILABLE", view.getVerdict());
        assertEquals(0, view.getMissingCount());
        assertTrue(view.getGroups().isEmpty());
    }

    @Test
    void learningStates_onlyCompletedUnlocks() {
        List<LessonCourse> courses = List.of(
                course(1, "C-1", "OPEN"),
                course(2, "C-2", "OPEN"),
                course(3, "C-3", "OPEN"),
                course(4, "C-4", "OPEN"),
                course(5, "C-5", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(
                group(10, 2, 1, "ALL"),
                group(20, 3, 1, "ALL"),
                group(30, 4, 1, "ALL"),
                group(40, 5, 1, "ALL"));
        List<CoursePrerequisite> items = List.of(
                prereq(1, 10, 1), prereq(2, 20, 1),
                prereq(3, 30, 1), prereq(4, 40, 1));
        // 同一门先修课在四种记录下的结论
        List<Enrollment> records = List.of(
                enrollment(1, 1, "COMPLETED"),
                enrollment(2, 1, "NORMAL"),
                enrollment(3, 1, "CANCELED"),
                enrollment(4, 1, null));
        when(lessonCourseMapper.findAll()).thenReturn(courses);
        when(prereqGroupMapper.findAll()).thenReturn(groups);
        when(prerequisiteMapper.findAll()).thenReturn(items);
        // 学员1 已完成 → 可学；学员2 在学 / 学员3 取消 / 学员4 状态缺失 → 受阻
        CoursePathService s1 = new CoursePathService(lessonCourseMapper, prereqGroupMapper,
                prerequisiteMapper, enrollmentMapper);
        when(enrollmentMapper.listPlainByStudentId(1L)).thenReturn(List.of(records.get(0)));
        assertEquals("AVAILABLE", s1.resolve(1L, 2L).getVerdict());
        when(enrollmentMapper.listPlainByStudentId(2L)).thenReturn(List.of(records.get(1)));
        assertEquals("BLOCKED", s1.resolve(2L, 3L).getVerdict());
        assertEquals("NORMAL", s1.resolve(2L, 3L).getGroups().get(0).getItems().get(0).getLearningState());
        when(enrollmentMapper.listPlainByStudentId(3L)).thenReturn(List.of(records.get(2)));
        assertEquals("BLOCKED", s1.resolve(3L, 4L).getVerdict());
        when(enrollmentMapper.listPlainByStudentId(4L)).thenReturn(List.of(records.get(3)));
        CoursePathView missingState = s1.resolve(4L, 5L);
        assertEquals("BLOCKED", missingState.getVerdict());
        assertEquals("LEGACY_MISSING",
                missingState.getGroups().get(0).getItems().get(0).getLearningState());
    }

    // ---------- 三层依赖：逐级解析多级，缺口路径先修在前 ----------

    @Test
    void threeLevelChain_resolvesMultiLevelGapPath() {
        List<LessonCourse> courses = List.of(
                course(1, "C-6101", "OPEN"), course(2, "C-6102", "OPEN"),
                course(3, "C-6103", "OPEN"), course(4, "C-6104", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(
                group(1, 2, 1, "ALL"), group(2, 3, 1, "ALL"), group(3, 4, 1, "ALL"));
        List<CoursePrerequisite> items = List.of(
                prereq(1, 1, 1), prereq(2, 2, 2), prereq(3, 3, 3));
        // 学员完成了 C-6101
        CoursePathService service = serviceWith(courses, groups, items,
                List.of(enrollment(1, 1, "COMPLETED")));
        Map<Long, CoursePathView> views = resolveAll(service, 1L, courses);

        assertEquals("AVAILABLE", views.get(1L).getVerdict());
        CoursePathView target = views.get(4L);
        assertEquals("BLOCKED", target.getVerdict());
        assertEquals(2, target.getMissingCount());
        // 最短缺口路径：C-6102 → C-6103 → 目标 C-6104
        assertEquals(Arrays.asList(2L, 3L, 4L), gapIds(target));
        // 缺口层级=最深先修缺课距目标的层数（C-6102 为第 2 层；目标自身不计入层级）
        assertEquals(2, target.getMissingLevel());
    }

    // ---------- 等价先修组：任意一门满足即整组满足，不重复计数 ----------

    @Test
    void anyGroup_oneCompletedSatisfies_andNotDoubleCounted() {
        List<LessonCourse> courses = List.of(
                course(1, "C-A", "OPEN"), course(2, "C-B", "OPEN"),
                course(3, "C-C", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(group(10, 3, 1, "ANY"));
        List<CoursePrerequisite> items = List.of(prereq(1, 10, 1), prereq(2, 10, 2));
        CoursePathService done = serviceWith(courses, groups, items,
                List.of(enrollment(1, 2, "COMPLETED")));
        CoursePathView okView = done.resolve(1L, 3L);
        assertEquals("AVAILABLE", okView.getVerdict());
        assertEquals(0, okView.getMissingCount());
        assertTrue(okView.getGroups().get(0).isSatisfied());

        // 两门都没完成：只计一条最短路线的缺口（1 门），而不是两门都缺（2 门）
        CoursePathService none = serviceWith(courses, groups, items, List.of());
        CoursePathView blocked = none.resolve(1L, 3L);
        assertEquals("BLOCKED", blocked.getVerdict());
        assertEquals(1, blocked.getMissingCount());
        assertEquals(Arrays.asList(1L, 3L), gapIds(blocked));
    }

    // ---------- 多路线择优：缺失最少 → 层级最浅 → 课程编号 ----------

    @Test
    void routeSelection_prefersFewerMissing() {
        // 目标 <- ANY(多门短链 3[<-2<-1], 单层入口 4)；学员完成 1、2 → 短链缺 1，入口缺 1
        // —— 缺失数相同（都是 1 门）、层级：3 为 1 层（其先修已完成）、4 为 1 层 → 编号 C-003 胜
        List<LessonCourse> courses = List.of(
                course(1, "C-001", "OPEN"), course(2, "C-002", "OPEN"),
                course(3, "C-003", "OPEN"), course(4, "C-004", "OPEN"),
                course(5, "C-005", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(
                group(1, 2, 1, "ALL"), group(2, 3, 1, "ALL"),
                group(9, 5, 1, "ANY"));
        List<CoursePrerequisite> items = List.of(
                prereq(1, 1, 1), prereq(2, 2, 2),
                prereq(9, 9, 3), prereq(10, 9, 4));
        CoursePathService service = serviceWith(courses, groups, items,
                List.of(enrollment(1, 1, "COMPLETED"), enrollment(1, 2, "COMPLETED")));
        CoursePathView view = service.resolve(1L, 5L);
        assertEquals("BLOCKED", view.getVerdict());
        assertEquals(1, view.getMissingCount());
        // 两条路线都只缺 1 门且层级相同 → 课程编号更小的 C-003
        assertEquals(List.of(3L), view.getGapPath().stream()
                .filter(g -> g.getCourseId() != 5L).map(g -> g.getCourseId()).toList());

        // 无记录学员：短链缺 3 门，单层入口只缺 1 门 → 缺失最少的 C-004 胜
        CoursePathService none = serviceWith(courses, groups, items, List.of());
        CoursePathView noneView = none.resolve(1L, 5L);
        assertEquals(1, noneView.getMissingCount());
        assertEquals(List.of(4L), noneView.getGapPath().stream()
                .filter(g -> g.getCourseId() != 5L).map(g -> g.getCourseId()).toList());
    }

    @Test
    void routeSelection_shallowerWinsWhenMissingCountTies() {
        // ANY 两个入口都缺 1 门：入口 9 是两层（9<-8，学员完成 8 则 9 缺1层1；
        // 这里让学员一门都没完成，则 9 链缺 2 门，3 链缺 1 门）——
        // 为构造「同缺1门、不同层级」：目标 <- ANY(3[<-2 已完成], 9[<-8 已完成])
        List<LessonCourse> courses = List.of(
                course(2, "C-002", "OPEN"), course(3, "C-003", "OPEN"),
                course(8, "C-008", "OPEN"), course(9, "C-009", "OPEN"),
                course(20, "C-020", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(
                group(1, 3, 1, "ALL"), group(2, 9, 1, "ALL"),
                group(3, 20, 1, "ANY"));
        List<CoursePrerequisite> items = List.of(
                prereq(1, 1, 2), prereq(2, 2, 8),
                prereq(3, 3, 3), prereq(4, 3, 9));
        // 学员只完成 C-008：C-009 链上 C-009 自身仍缺（层级1，缺1）；
        // C-003 链：C-002 未完成 → C-003 不可行（ALL 链断裂）—— 改为学员完成 C-002：
        CoursePathService service = serviceWith(courses, groups, items,
                List.of(enrollment(1, 2, "COMPLETED"), enrollment(1, 8, "COMPLETED")));
        // 此时两条路线都缺 1 门（C-003 与 C-009）、层级都是 1 → 编号 C-003 胜
        CoursePathView sameLevel = service.resolve(1L, 20L);
        assertEquals(1, sameLevel.getMissingCount());
        assertEquals(List.of(3L), sameLevel.getGapPath().stream()
                .filter(g -> g.getCourseId() != 20L).map(g -> g.getCourseId()).toList());

        // 层级不同：学员完成 C-002 但没完成 C-008 → C-009 链缺 {8,9}=2 门（层级2），
        // C-003 链缺 {3}=1 门（层级1）→ 缺失少的 C-003 胜
        CoursePathService deeper = serviceWith(courses, groups, items,
                List.of(enrollment(1, 2, "COMPLETED")));
        CoursePathView deepView = deeper.resolve(1L, 20L);
        assertEquals(1, deepView.getMissingCount());
        assertEquals(1, deepView.getMissingLevel());
        assertEquals(List.of(3L), deepView.getGapPath().stream()
                .filter(g -> g.getCourseId() != 20L).map(g -> g.getCourseId()).toList());
    }

    // ---------- 扇出树 vs 线性链：同缺失数时层级最浅路线胜（真正的层级择优） ----------

    @Test
    void fanOutTree_beatsLinearChain_whenSameMissingCountShallower() {
        // 线性链：34 <- 33 <- 32 <- 31（4 门缺、深度4）
        // 扇出树：35 <- ALL(37 <- 39, 38)（39/38/37/35 共4门缺、深度3）
        // 目标 36 <- ANY(34, 35)，无记录学员
        List<LessonCourse> courses = List.of(
                course(31, "C-6131", "OPEN"), course(32, "C-6132", "OPEN"),
                course(33, "C-6133", "OPEN"), course(34, "C-6134", "OPEN"),
                course(35, "C-6135", "OPEN"), course(36, "C-6136", "OPEN"),
                course(37, "C-6137", "OPEN"), course(38, "C-6138", "OPEN"),
                course(39, "C-6139", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(
                group(121, 32, 1, "ALL"), group(122, 33, 1, "ALL"), group(123, 34, 1, "ALL"),
                group(124, 37, 1, "ALL"), group(125, 35, 1, "ALL"),
                group(126, 36, 1, "ANY"));
        List<CoursePrerequisite> items = List.of(
                prereq(29, 121, 31), prereq(30, 122, 32), prereq(31, 123, 33),
                prereq(32, 124, 39), prereq(33, 125, 37), prereq(34, 125, 38),
                prereq(35, 126, 34), prereq(36, 126, 35));
        CoursePathService service = serviceWith(courses, groups, items, List.of());
        CoursePathView view = service.resolve(1L, 36L);
        assertEquals("BLOCKED", view.getVerdict());
        assertEquals(4, view.getMissingCount());
        // 线性链深度 4、扇出树深度 3 → 选扇出树
        assertEquals(3, view.getMissingLevel());
        List<Long> gapChain = view.getGapPath().stream().map(GapCourse::getCourseId).toList();
        assertEquals(List.of(39L, 37L, 38L, 35L, 36L), gapChain);
        // 共享/并集去重：4 个先修缺口 + 目标自身，35 只出现一次
        assertEquals(5, gapChain.size());
    }

    // ---------- 停开课：历史已完成仍解锁；未完成不可行且不能新学 ----------

    @Test
    void closedCourse_completed_stillUnlocks() {
        List<LessonCourse> courses = List.of(
                course(4, "C-6004", "CLOSED"), course(13, "C-6112", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(group(1, 13, 1, "ALL"));
        List<CoursePrerequisite> items = List.of(prereq(1, 1, 4));
        CoursePathService done = serviceWith(courses, groups, items,
                List.of(enrollment(1, 4, "COMPLETED")));
        CoursePathView view = done.resolve(1L, 13L);
        assertEquals("AVAILABLE", view.getVerdict());
    }

    @Test
    void closedCourse_notCompleted_isInfeasibleRoute() {
        List<LessonCourse> courses = List.of(
                course(4, "C-6004", "CLOSED"), course(13, "C-6112", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(group(1, 13, 1, "ALL"));
        List<CoursePrerequisite> items = List.of(prereq(1, 1, 4));
        // 在学记录不算完成
        CoursePathService learning = serviceWith(courses, groups, items,
                List.of(enrollment(2, 4, "NORMAL")));
        CoursePathView view = learning.resolve(2L, 13L);
        assertEquals("BLOCKED", view.getVerdict());
        assertFalse(view.isDataGap());
        assertTrue(view.getGapPath().stream().anyMatch(g -> g.getCourseId() == 4L && g.isClosed()));

        // 无记录的停开课自身：可解析、受阻、closed
        CoursePathService none = serviceWith(courses, groups, items, List.of());
        CoursePathView self = none.resolve(2L, 4L);
        assertEquals("BLOCKED", self.getVerdict());
        assertTrue(self.isClosed());
    }

    // ---------- 关联缺失：占位 + 数据缺口，不自动解锁 ----------

    @Test
    void missingPrerequisiteCourse_isPlaceholderDataGap() {
        List<LessonCourse> courses = List.of(course(23, "C-6122", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(group(1, 23, 1, "ALL"));
        List<CoursePrerequisite> items = List.of(prereq(1, 1, 999001L));
        CoursePathService service = serviceWith(courses, groups, items, List.of());
        CoursePathView view = service.resolve(1L, 23L);
        assertEquals("BLOCKED", view.getVerdict());
        assertTrue(view.isDataGap());
        assertEquals(999001L, view.getGapPath().get(0).getCourseId());
        assertTrue(view.getGapPath().get(0).isMissing());
    }

    // ---------- 环路：自指 / 两环 都终止并给可读循环路径，不无限递归 ----------

    @Test
    void selfCycle_isReportedReadably_andTerminates() {
        List<LessonCourse> courses = List.of(course(24, "C-6123", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(group(1, 24, 1, "ALL"));
        List<CoursePrerequisite> items = List.of(prereq(1, 1, 24));
        CoursePathService service = serviceWith(courses, groups, items, List.of());
        CoursePathView view = service.resolve(1L, 24L);
        assertEquals("CYCLE", view.getVerdict());
        assertEquals("C-6123 → C-6123", view.getCyclePath());
        // 约课闸门对环路给出中文错误而不是栈溢出
        assertBiz(() -> service.assertEnrollable(1L, List.of(24L), null));
    }

    @Test
    void twoNodeCycle_andCycleDeepInChain_reportCycle() {
        List<LessonCourse> courses = List.of(
                course(25, "C-6124", "OPEN"), course(26, "C-6125", "OPEN"),
                course(27, "C-6126", "OPEN"));
        List<CoursePrereqGroup> groups = List.of(
                group(1, 25, 1, "ALL"), group(2, 26, 1, "ALL"), group(3, 27, 1, "ALL"));
        List<CoursePrerequisite> items = List.of(
                prereq(1, 1, 26), prereq(2, 2, 25), prereq(3, 3, 26));
        CoursePathService service = serviceWith(courses, groups, items, List.of());

        CoursePathView a = service.resolve(1L, 25L);
        assertEquals("CYCLE", a.getVerdict());
        // 从 C-6124 出发：C-6124 → C-6125 → C-6124
        assertEquals("C-6124 → C-6125 → C-6124", a.getCyclePath());

        // 环在深链上：目标 C-6126 也判定为循环而不是无限递归
        CoursePathView deep = service.resolve(1L, 27L);
        assertEquals("CYCLE", deep.getVerdict());
        assertTrue(deep.getCyclePath().contains("C-6124"));
        assertTrue(deep.getCyclePath().contains("C-6125"));
    }

    // ---------- 批量视角：空记录、全部课程一次解析稳定 ----------

    @Test
    void resolveAll_emptyRecords_stableAndConstantShape() {
        List<LessonCourse> courses = new ArrayList<>();
        courses.add(course(1, "C-1", "OPEN"));
        courses.add(course(2, "C-2", "CLOSED"));
        List<CoursePrereqGroup> groups = List.of(group(1, 1, 1, "ALL"));
        List<CoursePrerequisite> items = List.of(prereq(1, 1, 2));
        CoursePathService service = serviceWith(courses, groups, items, List.of());
        List<CoursePathView> all = service.resolveAll(1L);
        assertEquals(2, all.size());
        // 无任何记录：C-1 因停开先修受阻；重复解析结论完全一致
        CoursePathView first = service.resolve(1L, 1L);
        CoursePathView second = service.resolve(1L, 1L);
        assertEquals(first.getSummary(), second.getSummary());
        assertEquals(first.getMissingCount(), second.getMissingCount());
        assertEquals(gapIds(first), gapIds(second));
    }

    private void assertBiz(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("期望抛出 BizException");
        } catch (BizException expected) {
            assertTrue(expected.getMessage().contains("环路"));
        }
    }
}

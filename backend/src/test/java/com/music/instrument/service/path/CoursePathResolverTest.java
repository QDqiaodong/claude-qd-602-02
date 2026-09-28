package com.music.instrument.service.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.music.instrument.domain.CoursePrerequisite;
import com.music.instrument.domain.LessonCourse;

/**
 * 课程路径解析器单测：三层依赖、等价先修组、多路线择优（缺口数→层级→编号）、
 * 停开课程、循环 / 自指、缺失关联、旧状态不解锁、共享先修去重。
 * 不依赖 Spring / 数据库，纯内存建图。
 */
class CoursePathResolverTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 6, 9, 0);

    private final List<LessonCourse> courses = new ArrayList<>();
    private final List<CoursePrerequisite> rels = new ArrayList<>();

    private LessonCourse course(long id, String no, String status) {
        LessonCourse c = new LessonCourse();
        c.setId(id);
        c.setCourseNo(no);
        c.setTitle(no + "名称");
        c.setTeacherName("教师");
        c.setCategory("PIANO");
        c.setCapacity(6);
        c.setEnrolledCount(0);
        c.setPrice(new BigDecimal("1000"));
        c.setStatus(status);
        c.setCreatedAt(T);
        courses.add(c);
        return c;
    }

    private void rel(long id, long course, long prereq, String type, Integer group) {
        CoursePrerequisite r = new CoursePrerequisite();
        r.setId(id);
        r.setCourseId(course);
        r.setPrereqId(prereq);
        r.setRequireType(type);
        r.setGroupNo(group);
        r.setCreatedAt(T);
        rels.add(r);
    }

    private CoursePathResolver resolver(Set<Long> completed, Set<Long> learning) {
        return new CoursePathResolver(new PathSnapshot(courses, rels,
                completed == null ? Set.of() : completed,
                learning == null ? Set.of() : learning));
    }

    private CoursePathResolver.Plan plan(CoursePathResolver r, long id) {
        return r.resolve(id);
    }

    @Test
    void noPrerequisite_isReady_byDefault() {
        course(1, "C-1001", "OPEN");
        CoursePathResolver.Plan p = plan(resolver(Set.of(), Set.of()), 1);
        assertTrue(p.isSatisfied());
        assertTrue(p.isViable());
        // 自身未完成所以在缺口集合里，但视图装配会把目标自身去掉
        assertTrue(p.getGapIds().contains(1L));
    }

    @Test
    void onlyCompletedUnlocks_learningAndNormalDoNot() {
        course(1, "C-1001", "OPEN");
        course(2, "C-1002", "OPEN");
        rel(1, 2, 1, "ALL", null);

        // 在学记录不解锁：2 仍受阻；CANCELED 不在 learning 集合中，效果同样受阻
        CoursePathResolver.Plan blocked = plan(resolver(Set.of(), Set.of(1L)), 2);
        assertFalse(blocked.isSatisfied());
        // 已完成解锁
        CoursePathResolver.Plan done = plan(resolver(Set.of(1L), Set.of()), 2);
        assertTrue(done.isSatisfied());
        assertTrue(done.isViable());
        assertEquals(0, done.getChosenPrereqs().get(0).getGapIds().size());
    }

    @Test
    void threeLevelChain_resolvesDeepAndShortestGap() {
        course(8, "C-1008", "OPEN");
        course(7, "C-1007", "OPEN");
        course(6, "C-1006", "OPEN");
        course(20, "C-1020", "OPEN");
        rel(1, 6, 7, "ALL", null);
        rel(2, 7, 8, "ALL", null);
        rel(3, 20, 6, "ALL", null);

        // 无任何完成：20 的缺口 {6,7,8,20}，深度 4
        CoursePathResolver.Plan p = plan(resolver(Set.of(), Set.of()), 20);
        assertFalse(p.isSatisfied());
        assertEquals(Set.of(6L, 7L, 8L, 20L), p.getGapIds());
        assertEquals(4, p.getDepth());

        // 完成最底层 8：缺口 {6,7,20}
        CoursePathResolver.Plan p2 = plan(resolver(Set.of(8L), Set.of()), 20);
        assertEquals(Set.of(6L, 7L, 20L), p2.getGapIds());
        assertFalse(p2.isSatisfied());

        // 全部完成：可学
        assertTrue(plan(resolver(Set.of(6L, 7L, 8L), Set.of()), 20).isSatisfied());
    }

    @Test
    void electiveGroup_anyOneSatisfies_andNotDoubleCounted() {
        course(2, "C-1002", "OPEN");
        course(13, "C-1013", "OPEN");
        course(9, "C-1009", "OPEN");
        rel(1, 9, 2, "ELECTIVE", 1);
        rel(2, 9, 13, "ELECTIVE", 1);

        // 两个都没完成：缺口只算一门（组不重复计数），按编号选 2
        CoursePathResolver.Plan none = plan(resolver(Set.of(), Set.of()), 9);
        assertFalse(none.isSatisfied());
        assertEquals(Set.of(2L, 9L), none.getGapIds());

        // 完成组内任意一门（13）：满足
        assertTrue(plan(resolver(Set.of(13L), Set.of()), 9).isSatisfied());
        // 完成 2：满足
        assertTrue(plan(resolver(Set.of(2L), Set.of()), 9).isSatisfied());
    }

    @Test
    void multipleRoutes_preferFewerMissingThenShallowThenCourseNo() {
        // 组 [6 | 3]：6 → 7 → 8（6 未完成且 7 未完成，缺 {6,7}），3 无先修（缺 {3}）→ 选 3
        course(8, "C-1008", "OPEN");
        course(7, "C-1007", "OPEN");
        course(6, "C-1006", "OPEN");
        course(3, "C-1003", "OPEN");
        course(19, "C-1019", "OPEN");
        rel(1, 6, 7, "ALL", null);
        rel(2, 7, 8, "ALL", null);
        rel(3, 19, 6, "ELECTIVE", 1);
        rel(4, 19, 3, "ELECTIVE", 1);

        CoursePathResolver.Plan p = plan(resolver(Set.of(8L), Set.of()), 19);
        CoursePathResolver.Plan chosen = p.getChosenPrereqs().get(0);
        assertEquals(3L, chosen.getCourseId());
        assertEquals(Set.of(3L, 19L), p.getGapIds());
    }

    @Test
    void equalMissing_shallowRouteWins() {
        // 28 组 [29 | 32]：29 需 30+31（缺 {29,30,31} 深2）；32→33→34（缺 {32,33,34} 深3）→ 选 29
        for (long id : new long[]{28, 29, 30, 31, 32, 33, 34}) {
            course(id, String.format("C-10%02d", id), "OPEN");
        }
        rel(1, 28, 29, "ELECTIVE", 1);
        rel(2, 28, 32, "ELECTIVE", 1);
        rel(3, 29, 30, "ALL", null);
        rel(4, 29, 31, "ALL", null);
        rel(5, 32, 33, "ALL", null);
        rel(6, 33, 34, "ALL", null);

        CoursePathResolver.Plan p = plan(resolver(Set.of(), Set.of()), 28);
        CoursePathResolver.Plan chosen = p.getChosenPrereqs().get(0);
        assertEquals(29L, chosen.getCourseId());
        assertEquals(2, chosen.getDepth());
        assertEquals(Set.of(29L, 30L, 31L, 28L), p.getGapIds());
    }

    @Test
    void equalMissingAndDepth_lowerCourseNoWins() {
        // 18 组 [1 | 2]：都缺 1 门、都深 1 → 选编号小的 1
        course(1, "C-1001", "OPEN");
        course(2, "C-1002", "OPEN");
        course(18, "C-1018", "OPEN");
        rel(1, 18, 1, "ELECTIVE", 1);
        rel(2, 18, 2, "ELECTIVE", 1);

        CoursePathResolver.Plan p = plan(resolver(Set.of(), Set.of()), 18);
        assertEquals(1L, p.getChosenPrereqs().get(0).getCourseId());
    }

    @Test
    void closedCourse_completedStillUnlocks_butCannotBeNewTarget() {
        course(8, "C-1008", "OPEN");
        course(10, "C-1010", "CLOSED");
        course(22, "C-1022", "OPEN");
        rel(1, 10, 8, "ALL", null);
        rel(2, 22, 10, "ALL", null);

        // 停开课已完成：后继 22 可学
        CoursePathResolver.Plan viaDone = plan(resolver(Set.of(8L, 10L), Set.of()), 22);
        assertTrue(viaDone.isSatisfied());
        assertTrue(viaDone.isViable());

        // 停开课未完成：自身不可作为新目标
        CoursePathResolver.Plan closed = plan(resolver(Set.of(8L), Set.of()), 10);
        assertFalse(closed.isOpen());
        assertFalse(closed.isViable());
        assertTrue(closed.isClosedUnfinished());
    }

    @Test
    void closedUnfinishedPrereq_routeInfeasible_otherElectiveWins() {
        // 23 组 [4(停开未完成) | 15(已完成)] → 自动选 15
        course(4, "C-1004", "CLOSED");
        course(15, "C-1015", "OPEN");
        course(23, "C-1023", "OPEN");
        rel(1, 23, 4, "ELECTIVE", 1);
        rel(2, 23, 15, "ELECTIVE", 1);

        CoursePathResolver.Plan p = plan(resolver(Set.of(15L), Set.of()), 23);
        assertEquals(15L, p.getChosenPrereqs().get(0).getCourseId());
        assertTrue(p.isSatisfied());
    }

    @Test
    void selfReference_terminatesWithCycle() {
        course(12, "C-1012", "OPEN");
        rel(1, 12, 12, "ALL", null);

        CoursePathResolver.Plan p = plan(resolver(Set.of(), Set.of()), 12);
        assertTrue(p.isCyclic());
        assertFalse(p.isViable());
        assertEquals(List.of("C-1012", "C-1012"), p.getCyclePath());
    }

    @Test
    void cycleBetweenTwo_terminatesWithReadablePath() {
        course(13, "C-1013", "OPEN");
        course(14, "C-1014", "OPEN");
        rel(1, 13, 14, "ALL", null);
        rel(2, 14, 13, "ALL", null);

        CoursePathResolver.Plan p13 = plan(resolver(Set.of(), Set.of()), 13);
        assertTrue(p13.isCyclic());
        assertEquals(List.of("C-1013", "C-1014", "C-1013"), p13.getCyclePath());

        // 多次解析稳定，不随遍历方向漂移
        CoursePathResolver r = resolver(Set.of(), Set.of());
        assertEquals(p13.getCyclePath(), r.resolve(13L).getCyclePath());
        assertEquals(p13.getCyclePath(), r.resolve(13L).getCyclePath());
    }

    @Test
    void multiLevelDependentOnCycle_terminatesWithoutInfiniteRecursion() {
        course(13, "C-1013", "OPEN");
        course(14, "C-1014", "OPEN");
        course(17, "C-1017", "OPEN");
        rel(1, 13, 14, "ALL", null);
        rel(2, 14, 13, "ALL", null);
        rel(3, 17, 13, "ALL", null);

        CoursePathResolver.Plan p = plan(resolver(Set.of(), Set.of()), 17);
        assertTrue(p.isCyclic());
        assertFalse(p.isViable());
        assertEquals(List.of("C-1013", "C-1014", "C-1013"), p.getCyclePath());
    }

    @Test
    void electiveWithCyclicMember_picksHealthyAlternative() {
        // 9 组 [2(可完成) | 13(环上)]：存在健康成员时必须绕开循环路线
        course(2, "C-1002", "OPEN");
        course(13, "C-1013", "OPEN");
        course(14, "C-1014", "OPEN");
        course(9, "C-1009", "OPEN");
        rel(1, 9, 2, "ELECTIVE", 1);
        rel(2, 9, 13, "ELECTIVE", 1);
        rel(3, 13, 14, "ALL", null);
        rel(4, 14, 13, "ALL", null);

        // 2 未完成但路线健康：选 2，结论只是普通受阻，不带循环
        CoursePathResolver.Plan p = plan(resolver(Set.of(), Set.of()), 9);
        assertEquals(2L, p.getChosenPrereqs().get(0).getCourseId());
        assertFalse(p.isCyclic());
        assertFalse(p.isDataGap());
    }

    @Test
    void missingPrerequisiteCourse_isPlaceholderDataGap() {
        course(16, "C-1016", "OPEN");
        rel(1, 16, 199, "ALL", null);

        CoursePathResolver.Plan p = plan(resolver(Set.of(), Set.of()), 16);
        assertFalse(p.isViable());
        assertTrue(p.isDataGap());
        CoursePathResolver.Plan missing = p.getChosenPrereqs().get(0);
        assertTrue(missing.isPlaceholder());
        assertEquals(199L, missing.getCourseId());
    }

    @Test
    void missingTargetCourse_isPlaceholder() {
        CoursePathResolver.Plan p = plan(resolver(Set.of(), Set.of()), 404);
        assertTrue(p.isPlaceholder());
        assertTrue(p.isDataGap());
    }

    @Test
    void sharedPrerequisite_nodeCountedOnce() {
        // 11 需 8（ALL）+ 组[2|3]；8 已完成 → 缺口 {2,11}（选 2，编号小）
        course(8, "C-1008", "OPEN");
        course(2, "C-1002", "OPEN");
        course(3, "C-1003", "OPEN");
        course(11, "C-1011", "OPEN");
        rel(1, 11, 8, "ALL", null);
        rel(2, 11, 2, "ELECTIVE", 1);
        rel(3, 11, 3, "ELECTIVE", 1);

        CoursePathResolver.Plan p = plan(resolver(Set.of(8L), Set.of()), 11);
        assertEquals(Set.of(2L, 11L), p.getGapIds());
        assertEquals(2L, p.getChosenPrereqs().get(1).getCourseId());
    }

    @Test
    void canceledAndUnknownStatus_doNotUnlock() {
        // 27 → 5（学员在 5 上只有 CANCELED 记录）：CANCELED 既不在 completed 也不在 learning
        course(5, "C-1005", "OPEN");
        course(27, "C-1027", "OPEN");
        rel(1, 27, 5, "ALL", null);

        CoursePathResolver.Plan p = plan(resolver(Set.of(), Set.of()), 27);
        assertFalse(p.isSatisfied());
        CoursePathResolver.Plan prereq = p.getChosenPrereqs().get(0);
        assertEquals(5L, prereq.getCourseId());
        assertFalse(prereq.isCompleted());
        assertFalse(prereq.isLearning());
    }

    @Test
    void resolverIsDeterministicAcrossRuns() {
        course(29, "C-1029", "OPEN");
        course(30, "C-1030", "OPEN");
        course(31, "C-1031", "OPEN");
        course(32, "C-1032", "OPEN");
        course(33, "C-1033", "OPEN");
        course(34, "C-1034", "OPEN");
        course(28, "C-1028", "OPEN");
        rel(1, 28, 29, "ELECTIVE", 1);
        rel(2, 28, 32, "ELECTIVE", 1);
        rel(3, 29, 30, "ALL", null);
        rel(4, 29, 31, "ALL", null);
        rel(5, 32, 33, "ALL", null);
        rel(6, 33, 34, "ALL", null);

        Set<Long> gaps1 = new LinkedHashSet<>(plan(resolver(Set.of(), Set.of()), 28).getGapIds());
        Set<Long> gaps2 = new LinkedHashSet<>(plan(resolver(Set.of(), Set.of()), 28).getGapIds());
        assertEquals(gaps1, gaps2);
    }
}

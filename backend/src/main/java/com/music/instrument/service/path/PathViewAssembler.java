package com.music.instrument.service.path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.music.instrument.domain.Enrollment;
import com.music.instrument.domain.Student;
import com.music.instrument.dto.view.CoursePathView;
import com.music.instrument.dto.view.StudentPathBundleView;

/**
 * 把 {@link CoursePathResolver} 的解析树装配成对外视图。
 * 纯装配，不含任何数据库访问 —— 列表、详情、刷新、约课守卫共用，结论天然一致。
 */
public final class PathViewAssembler {

    private PathViewAssembler() {
    }

    /** 单门课程路径视图（课程列表 / 课程详情 / 约课守卫共用）。 */
    public static CoursePathView toView(CoursePathResolver resolver,
                                        CoursePathResolver.Plan root,
                                        Student student,
                                        Map<Long, String> learningStatusByCourse) {
        CoursePathView view = new CoursePathView();
        view.setStudentId(student == null ? null : student.getId());
        view.setStudentName(student == null ? "缺失学员" : student.getName());

        view.setCourseId(root.getCourseId());
        view.setCourseNo(root.getCourseNo());
        view.setTitle(root.getTitle());
        view.setPlaceholder(root.isPlaceholder());
        view.setOpen(root.isOpen());
        view.setDataGap(root.isDataGap());
        view.setCyclePath(root.getCyclePath());
        view.setCourseStatus(root.isPlaceholder() ? "MISSING" : (root.isOpen() ? "OPEN" : "CLOSED"));

        String learnStatus = learningStatusByCourse == null
                ? "NONE" : learningStatusByCourse.getOrDefault(root.getCourseId(), "NONE");
        view.setLearningStatus(learnStatus);
        view.setLearningStatusName(learningStatusName(learnStatus));

        // 缺口路径：沿选定路线深→浅收集未完成的先修节点（不含目标自身）
        List<CoursePathView.GapNode> missing = collectMissing(root);
        missing.sort(Comparator.comparing(CoursePathView.GapNode::getDepth).reversed()
                .thenComparing(CoursePathView.GapNode::getCourseNo)
                .thenComparing(CoursePathView.GapNode::getCourseId));
        view.setMissingPath(missing);
        view.setMissingCount(missing.size());

        view.setRequirements(buildRequirements(resolver, root));

        view.setConclusion(resolveConclusion(root, learnStatus));
        view.setConclusionName(conclusionName(view.getConclusion()));
        return view;
    }

    /**
     * 结论口径（列表 / 详情 / 约课守卫唯一出口）：
     * 占位缺失课程 → DATA_GAP；命中环 → CYCLE；目标已停开 → CLOSED_TARGET（即使学员已完成，
     * 停开课也不能再作为新的学习目标；其「已完成可解锁后继」由后继节点解析保证）；
     * 路线含数据缺口 → DATA_GAP；先修未满足 → BLOCKED；
     * 先修满足且在学（含旧状态）→ LEARNING；否则 READY。
     */
    public static String resolveConclusion(CoursePathResolver.Plan root, String learningStatus) {
        if (root.isPlaceholder()) {
            return "DATA_GAP";
        }
        if (root.isCyclic()) {
            return "CYCLE";
        }
        if (!root.isOpen()) {
            return "CLOSED_TARGET";
        }
        if (root.isDataGap()) {
            return "DATA_GAP";
        }
        if (!root.isSatisfied()) {
            return "BLOCKED";
        }
        if ("LEARNING".equals(learningStatus) || "NORMAL".equals(learningStatus)
                || "UNKNOWN".equals(learningStatus)) {
            return "LEARNING";
        }
        return "READY";
    }

    public static String conclusionName(String conclusion) {
        return switch (conclusion) {
            case "READY" -> "可学";
            case "LEARNING" -> "正在学习";
            case "BLOCKED" -> "受阻";
            case "CLOSED_TARGET" -> "已停开";
            case "CYCLE" -> "循环依赖";
            case "DATA_GAP" -> "数据缺口";
            default -> conclusion;
        };
    }

    /** 学习状态文案：旧状态与缺失状态保留展示，但不映射为已完成。 */
    public static String learningStatusName(String status) {
        if (status == null) {
            return "无记录";
        }
        return switch (status) {
            case "COMPLETED" -> "已完成";
            case "LEARNING" -> "正在学习";
            case "NORMAL" -> "正常（旧记录）";
            case "CANCELED" -> "已退课";
            case "UNKNOWN" -> "状态缺失";
            case "NONE" -> "无记录";
            default -> status;
        };
    }

    private static List<CoursePathView.GapNode> collectMissing(CoursePathResolver.Plan root) {
        List<CoursePathView.GapNode> out = new ArrayList<>();
        Set<Long> emitted = new HashSet<>();
        dfs(root, 0, emitted, out, new HashSet<>());
        return out;
    }

    /**
     * 沿选定路线树深→浅收集缺口；completed 节点整棵子树都满足，剪枝不下钻。
     * active 为本次下钻路径，防止循环脏数据（12→12、13⇄14）在视图遍历时无限递归 ——
     * 解析器已经给出结论与 cyclePath，这里只负责展示缺口。
     */
    private static void dfs(CoursePathResolver.Plan plan, int depth, Set<Long> emitted,
                            List<CoursePathView.GapNode> out, Set<Long> active) {
        if (plan == null || !active.add(plan.getCourseId())) {
            return;
        }
        if (depth > 0) {
            if (plan.isCompleted()) {
                active.remove(plan.getCourseId());
                return; // 已完成先修：它与其子树都不是缺口
            }
            if (emitted.add(plan.getCourseId())) {
                out.add(toGapNode(plan, depth));
            }
        }
        List<CoursePathResolver.Plan> children = new ArrayList<>(plan.getChosenPrereqs());
        children.sort(Comparator.comparing(CoursePathResolver.Plan::getCourseNo)
                .thenComparing(CoursePathResolver.Plan::getCourseId));
        for (CoursePathResolver.Plan child : children) {
            dfs(child, depth + 1, emitted, out, active);
        }
        active.remove(plan.getCourseId());
    }

    private static CoursePathView.GapNode toGapNode(CoursePathResolver.Plan plan, int depth) {
        CoursePathView.GapNode node = new CoursePathView.GapNode();
        node.setCourseId(plan.getCourseId());
        node.setCourseNo(plan.getCourseNo());
        node.setTitle(plan.getTitle());
        node.setDepth(depth);
        node.setPlaceholder(plan.isPlaceholder());
        node.setOpen(plan.isOpen());
        node.setStatus(plan.isPlaceholder() ? "MISSING" : (plan.isOpen() ? "OPEN" : "CLOSED"));
        return node;
    }

    /** 直接先修要求：ALL 每项一门；ELECTIVE 组列出全部成员（含落败备选）。 */
    private static List<CoursePathView.RequirementView> buildRequirements(CoursePathResolver resolver,
                                                                          CoursePathResolver.Plan root) {
        List<CoursePathView.RequirementView> out = new ArrayList<>();
        for (CoursePathResolver.Requirement req : root.getRequirements()) {
            CoursePathView.RequirementView rv = new CoursePathView.RequirementView();
            rv.setRequireType(req.isElective() ? "ELECTIVE" : "ALL");
            rv.setGroupNo(req.getGroupNo());
            List<CoursePathView.GapNode> courses = new ArrayList<>();
            for (Long prereqId : req.getPrereqIds()) {
                CoursePathResolver.Plan member = resolver.resolve(prereqId);
                courses.add(toGapNode(member, 1));
            }
            rv.setCourses(courses);
            out.add(rv);
        }
        return out;
    }

    /** 学员全部课程路径的批量装配（路径顺序由 orderedViews 给定，与单门接口同源）。 */
    public static StudentPathBundleView toBundle(Student student,
                                                 List<CoursePathView> orderedViews,
                                                 List<Enrollment> enrollments,
                                                 Function<Long, Boolean> courseExists) {
        StudentPathBundleView bundle = new StudentPathBundleView();
        bundle.setStudentId(student == null ? null : student.getId());
        bundle.setStudentNo(student == null ? null : student.getStudentNo());
        bundle.setStudentName(student == null ? "缺失学员" : student.getName());
        bundle.setPaths(orderedViews);

        // 学习记录关联课程缺失：保留占位，明确标为数据缺口
        List<CoursePathView.GapNode> orphans = new ArrayList<>();
        Set<Long> seenOrphan = new HashSet<>();
        for (Enrollment enrollment : enrollments) {
            Long courseId = enrollment.getCourseId();
            if (!Boolean.TRUE.equals(courseExists.apply(courseId)) && seenOrphan.add(courseId)) {
                CoursePathView.GapNode node = new CoursePathView.GapNode();
                node.setCourseId(courseId);
                node.setCourseNo("C-?" + courseId);
                node.setTitle("缺失课程（id=" + courseId + "）");
                node.setDepth(0);
                node.setPlaceholder(true);
                node.setOpen(false);
                node.setStatus("MISSING");
                orphans.add(node);
            }
        }
        bundle.setOrphanEnrollments(orphans);
        return bundle;
    }
}

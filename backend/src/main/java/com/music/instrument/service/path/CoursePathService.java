package com.music.instrument.service.path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.music.instrument.common.BizException;
import com.music.instrument.domain.CoursePrerequisite;
import com.music.instrument.domain.Enrollment;
import com.music.instrument.domain.LessonCourse;
import com.music.instrument.domain.Student;
import com.music.instrument.dto.view.CoursePathView;
import com.music.instrument.dto.view.StudentPathBundleView;
import com.music.instrument.mapper.CoursePrerequisiteMapper;
import com.music.instrument.mapper.EnrollmentMapper;
import com.music.instrument.mapper.LessonCourseMapper;
import com.music.instrument.mapper.StudentMapper;

/**
 * 学员课程路径应用服务：批量读取课程关系与学习记录（常数次查询），
 * 解析与展示统一走 {@link CoursePathResolver} + {@link PathViewAssembler}，
 * 单个约课、批量约课、列表 / 详情视图复用同一判定。
 */
@Service
public class CoursePathService {

    private final StudentMapper studentMapper;
    private final LessonCourseMapper lessonCourseMapper;
    private final EnrollmentMapper enrollmentMapper;
    private final CoursePrerequisiteMapper prerequisiteMapper;

    public CoursePathService(StudentMapper studentMapper,
                             LessonCourseMapper lessonCourseMapper,
                             EnrollmentMapper enrollmentMapper,
                             CoursePrerequisiteMapper prerequisiteMapper) {
        this.studentMapper = studentMapper;
        this.lessonCourseMapper = lessonCourseMapper;
        this.enrollmentMapper = enrollmentMapper;
        this.prerequisiteMapper = prerequisiteMapper;
    }

    /** 某学员一次查看全部课程路径：课程全量、关系全量、学员记录一次读，共常数条 SQL。 */
    public StudentPathBundleView pathsForStudent(Long studentId) {
        if (studentId == null) {
            throw new BizException("请先选择学员");
        }
        Student student = studentMapper.findById(studentId);
        if (student == null) {
            throw new BizException("学员不存在，无法查看课程路径");
        }

        Loaded loaded = loadAll(List.of(studentId));
        List<LessonCourse> courses = new ArrayList<>(loaded.courseMap.values());
        courses.sort(java.util.Comparator.comparing(LessonCourse::getCourseNo));
        CoursePathResolver resolver = new CoursePathResolver(loaded.snapshots.get(studentId));

        List<CoursePathView> views = new ArrayList<>();
        Map<Long, String> statusMap = loaded.learningStatus.get(studentId);
        for (LessonCourse course : courses) {
            // 每棵目标树独立遍历缓存：环路径始终相对本次根节点，结论不受根顺序影响
            CoursePathResolver.Plan plan = resolver.resolve(course.getId());
            views.add(enrich(PathViewAssembler.toView(resolver, plan, student, statusMap), course));
        }
        return PathViewAssembler.toBundle(student, views,
                loaded.enrollmentsByStudent.getOrDefault(studentId, List.of()),
                loaded.courseExists::get);
    }

    /** 单门课程路径（详情 / 刷新 / 守卫共用），同样是批量读取，不按深度追加查询。 */
    public CoursePathView pathForCourse(Long studentId, Long courseId) {
        if (studentId == null) {
            throw new BizException("请先选择学员");
        }
        if (courseId == null) {
            throw new BizException("请先选择课程");
        }
        Student student = studentMapper.findById(studentId);
        if (student == null) {
            throw new BizException("学员不存在，无法查看课程路径");
        }
        Loaded loaded = loadAll(List.of(studentId));
        CoursePathResolver resolver = new CoursePathResolver(loaded.snapshots.get(studentId));
        CoursePathResolver.Plan plan = resolver.resolve(courseId);
        CoursePathView view = PathViewAssembler.toView(resolver, plan, student,
                loaded.learningStatus.get(studentId));
        LessonCourse course = loaded.courseMap.get(courseId);
        return enrich(view, course);
    }

    /**
     * 约课先修守卫：单个 / 批量约课共用。
     * 只依据数据库里既有的 COMPLETED 记录；同批选中的课程不会被临时加入完成集，
     * 因此批量里互为先修的课程不能在同一批互相解锁。
     */
    public void guardEnroll(Student student, List<LessonCourse> targetCourses) {
        Loaded loaded = loadAll(List.of(student.getId()));
        CoursePathResolver resolver = new CoursePathResolver(loaded.snapshots.get(student.getId()));
        Map<Long, String> statusMap = loaded.learningStatus.get(student.getId());
        for (LessonCourse course : targetCourses) {
            CoursePathResolver.Plan plan = resolver.resolve(course.getId());
            String conclusion = PathViewAssembler.resolveConclusion(
                    plan, statusMap.getOrDefault(course.getId(), "NONE"));
            if ("READY".equals(conclusion) || "LEARNING".equals(conclusion)) {
                continue;
            }
            throw new BizException(buildRejectMessage(student, course, plan, conclusion));
        }
    }

    private String buildRejectMessage(Student student, LessonCourse course,
                                      CoursePathResolver.Plan plan, String conclusion) {
        String head = "学员【" + student.getName() + "】暂不能约课程【"
                + course.getCourseNo() + " " + course.getTitle() + "】：";
        return switch (conclusion) {
            case "CLOSED_TARGET" -> head + "该课程已停开，不能作为新的学习目标";
            case "CYCLE" -> head + "先修关系存在循环（" + String.join(" → ", plan.getCyclePath())
                    + "），请联系教务修正课程关系";
            case "DATA_GAP" -> head + "先修数据不完整，存在缺失课程，无法判定学习路径";
            default -> head + "仍缺少最短先修路径："
                    + formatGap(plan) + "，完成后再约";
        };
    }

    /** 缺口路径文案：直接沿选定路线树深→浅收集未完成先修（目标课自身不列出）。 */
    private String formatGap(CoursePathResolver.Plan plan) {
        List<CoursePathResolver.Plan> chain = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        collectGap(plan, 0, seen, chain);
        if (chain.isEmpty()) {
            return "先修条件未满足";
        }
        chain.sort(java.util.Comparator
                .comparingInt((CoursePathResolver.Plan p) -> -depthOf(p, plan))
                .thenComparing(CoursePathResolver.Plan::getCourseNo));
        List<String> labels = new ArrayList<>();
        for (CoursePathResolver.Plan node : chain) {
            labels.add(node.isPlaceholder()
                    ? node.getCourseNo()
                    : node.getCourseNo() + " " + node.getTitle());
        }
        return String.join(" → ", labels);
    }

    /** 深度优先收集未完成的先修缺口（深度 0 是目标自身，跳过）。 */
    private void collectGap(CoursePathResolver.Plan node, int depth,
                            java.util.Set<Long> seen, List<CoursePathResolver.Plan> out) {
        if (node == null || !seen.add(node.getCourseId())) {
            return;
        }
        if (depth > 0 && !node.isCompleted()) {
            out.add(node);
        }
        List<CoursePathResolver.Plan> children = new ArrayList<>(node.getChosenPrereqs());
        children.sort(java.util.Comparator.comparing(CoursePathResolver.Plan::getCourseNo)
                .thenComparing(CoursePathResolver.Plan::getCourseId));
        for (CoursePathResolver.Plan child : children) {
            collectGap(child, depth + 1, seen, out);
        }
    }

    private int depthOf(CoursePathResolver.Plan target, CoursePathResolver.Plan root) {
        int[] found = {-1};
        findDepth(root, 0, target.getCourseId(), found);
        return found[0] < 0 ? 0 : found[0];
    }

    private void findDepth(CoursePathResolver.Plan node, int depth, Long targetId, int[] found) {
        if (node == null || found[0] >= 0) {
            return;
        }
        if (node.getCourseId().equals(targetId)) {
            found[0] = depth;
            return;
        }
        for (CoursePathResolver.Plan child : node.getChosenPrereqs()) {
            findDepth(child, depth + 1, targetId, found);
        }
    }

    /** 补课程本体的教师等展示字段（解析器只带编号 / 名称 / 状态）。 */
    private CoursePathView enrich(CoursePathView view, LessonCourse course) {
        if (course != null) {
            view.setTeacherName(course.getTeacherName());
            if (!view.isPlaceholder()) {
                view.setCourseNo(course.getCourseNo());
                view.setTitle(course.getTitle());
            }
        }
        return view;
    }

    // ------------------------------------------------------------------
    // 批量加载：无论课程多少、依赖多深，都是固定几条 SQL：
    //   全部课程 1 + 全部关系 1 + 学员名单 1（IN）+ 每个学员学习记录 1
    // 学员路径页与约课守卫都走这里，绝不按课程或深度逐条查询。
    // ------------------------------------------------------------------
    Loaded loadAll(List<Long> studentIds) {
        Loaded loaded = new Loaded();
        loaded.allCourses = lessonCourseMapper.findAll();
        loaded.prerequisites = prerequisiteMapper.findAll();
        for (LessonCourse course : loaded.allCourses) {
            loaded.courseMap.put(course.getId(), course);
            loaded.courseExists.put(course.getId(), true);
        }

        List<Long> distinctStudents = new ArrayList<>(new LinkedHashSet<>(studentIds));
        if (distinctStudents.isEmpty()) {
            return new Loaded();
        }
        List<Student> students = studentMapper.findByIds(distinctStudents);

        for (Long studentId : distinctStudents) {
            List<Enrollment> records = enrollmentMapper.listByStudentId(studentId);
            loaded.enrollmentsByStudent.put(studentId, records);

            // 同一课程理论上只有一条（uk 约束）；脏数据下并存时 COMPLETED 优先
            Map<Long, String> statusByCourse = new HashMap<>();
            for (Enrollment record : records) {
                String normalized = normalizeStatus(record.getStatus());
                statusByCourse.merge(record.getCourseId(), normalized, this::strongerStatus);
            }
            Set<Long> completed = new LinkedHashSet<>();
            Set<Long> learning = new LinkedHashSet<>();
            for (Map.Entry<Long, String> entry : statusByCourse.entrySet()) {
                if ("COMPLETED".equals(entry.getValue())) {
                    completed.add(entry.getKey());
                } else if (!"CANCELED".equals(entry.getValue())) {
                    // LEARNING / NORMAL / UNKNOWN 都算「有在学/历史记录」，但不解锁
                    learning.add(entry.getKey());
                }
            }
            loaded.learningStatus.put(studentId, statusByCourse);
            loaded.snapshots.put(studentId,
                    new PathSnapshot(loaded.allCourses, loaded.prerequisites, completed, learning));
        }
        return loaded;
    }

    /**
     * 历史状态归一化：只有 COMPLETED 是已完成；CANCELED 已取消；LEARNING 在学；
     * NORMAL 为旧约课记录；null / 空串为历史状态缺失（UNKNOWN，照常展示但不解锁）。
     */
    private String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        return switch (raw) {
            case "COMPLETED", "LEARNING", "NORMAL", "CANCELED" -> raw;
            default -> "UNKNOWN";
        };
    }

    /** 同一课程多条脏记录时的状态优先级：COMPLETED > LEARNING > NORMAL > UNKNOWN > CANCELED。 */
    private String strongerStatus(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private int rank(String status) {
        return switch (status) {
            case "COMPLETED" -> 5;
            case "LEARNING" -> 4;
            case "NORMAL" -> 3;
            case "UNKNOWN" -> 2;
            case "CANCELED" -> 1;
            default -> 0;
        };
    }

    /** 一次批量加载的结果。 */
    static final class Loaded {
        List<LessonCourse> allCourses = List.of();
        List<CoursePrerequisite> prerequisites = List.of();
        final Map<Long, LessonCourse> courseMap = new LinkedHashMap<>();
        final Map<Long, Boolean> courseExists = new HashMap<>();
        final Map<Long, List<Enrollment>> enrollmentsByStudent = new LinkedHashMap<>();
        final Map<Long, Map<Long, String>> learningStatus = new LinkedHashMap<>();
        final Map<Long, PathSnapshot> snapshots = new LinkedHashMap<>();
    }
}

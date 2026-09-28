package com.music.instrument.service.path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.music.instrument.common.BizException;
import com.music.instrument.domain.CoursePrerequisite;
import com.music.instrument.domain.Enrollment;
import com.music.instrument.domain.LessonCourse;
import com.music.instrument.domain.Student;
import com.music.instrument.dto.view.CoursePathView;
import com.music.instrument.mapper.CoursePrerequisiteMapper;
import com.music.instrument.mapper.EnrollmentMapper;
import com.music.instrument.mapper.LessonCourseMapper;
import com.music.instrument.mapper.StudentMapper;
import com.music.instrument.service.path.CoursePathService;

/**
 * 路径服务层：批量读取规模、孤儿记录、约课守卫（单个 / 批量同一判定、同批不互相当完成）。
 */
@ExtendWith(MockitoExtension.class)
class CoursePathServiceTest {

    @Mock
    private StudentMapper studentMapper;
    @Mock
    private LessonCourseMapper lessonCourseMapper;
    @Mock
    private EnrollmentMapper enrollmentMapper;
    @Mock
    private CoursePrerequisiteMapper prerequisiteMapper;

    @InjectMocks
    private CoursePathService pathService;

    private Student student(long id, String name) {
        Student s = new Student();
        s.setId(id);
        s.setStudentNo("ST-" + id);
        s.setName(name);
        s.setPhone("1390000000" + id);
        s.setLevel("BEGINNER");
        s.setLessonBalance(20);
        return s;
    }

    private LessonCourse course(long id, String no, String status) {
        LessonCourse c = new LessonCourse();
        c.setId(id);
        c.setCourseNo(no);
        c.setTitle(no + "课");
        c.setTeacherName("师");
        c.setCategory("PIANO");
        c.setCapacity(6);
        c.setEnrolledCount(0);
        c.setStatus(status);
        return c;
    }

    private CoursePrerequisite rel(long course, long prereq) {
        CoursePrerequisite r = new CoursePrerequisite();
        r.setId(course * 10 + prereq);
        r.setCourseId(course);
        r.setPrereqId(prereq);
        r.setRequireType("ALL");
        return r;
    }

    private Enrollment enrollment(long student, long course, String status) {
        Enrollment e = new Enrollment();
        e.setId(student * 100 + course);
        e.setStudentId(student);
        e.setCourseId(course);
        e.setEnrollDate(LocalDate.of(2026, 9, 1));
        e.setLessons(2);
        e.setStatus(status);
        e.setCreatedAt(LocalDateTime.now());
        return e;
    }

    private void stubCommon(boolean withFindById) {
        if (withFindById) {
            when(studentMapper.findById(1L)).thenReturn(student(1, "韩青梧"));
        }
        when(studentMapper.findByIds(anyList())).thenReturn(List.of(student(1, "韩青梧")));
        when(lessonCourseMapper.findAll()).thenReturn(List.of(
                course(7, "C-1007", "OPEN"), course(6, "C-1006", "OPEN")));
        when(prerequisiteMapper.findAll()).thenReturn(List.of(rel(6, 7)));
    }

    @Test
    void pathsForStudent_batchLoaded_constantQueries() {
        stubCommon(true);
        // 学员只完成了 7
        when(enrollmentMapper.listByStudentId(1L))
                .thenReturn(List.of(enrollment(1, 7, "COMPLETED")));

        var bundle = pathService.pathsForStudent(1L);

        assertEquals(2, bundle.getPaths().size());
        // 课程 / 关系各只全量读一次，学员记录一次 —— 与课程数、深度无关
        verify(lessonCourseMapper, times(1)).findAll();
        verify(prerequisiteMapper, times(1)).findAll();
        verify(enrollmentMapper, times(1)).listByStudentId(1L);

        CoursePathView course6 = bundle.getPaths().stream()
                .filter(v -> v.getCourseId() == 6).findFirst().orElseThrow();
        assertEquals("READY", course6.getConclusion());
    }

    @Test
    void loadAll_multipleStudents_stillOneQueryPerKind() {
        // 多学员批量加载：课程 / 关系各一条，学员名单一条 IN，学习记录每学员一条
        when(studentMapper.findByIds(anyList())).thenReturn(
                List.of(student(1, "韩青梧"), student(3, "温叙白")));
        when(lessonCourseMapper.findAll()).thenReturn(List.of(course(7, "C-1007", "OPEN")));
        when(prerequisiteMapper.findAll()).thenReturn(List.of());
        when(enrollmentMapper.listByStudentId(1L)).thenReturn(List.of());
        when(enrollmentMapper.listByStudentId(3L)).thenReturn(
                List.of(enrollment(3, 7, "COMPLETED")));

        var loaded = pathService.loadAll(List.of(1L, 3L));

        assertEquals(2, loaded.snapshots.size());
        verify(lessonCourseMapper, times(1)).findAll();
        verify(prerequisiteMapper, times(1)).findAll();
        verify(studentMapper, times(1)).findByIds(anyList());
        verify(enrollmentMapper, times(1)).listByStudentId(1L);
        verify(enrollmentMapper, times(1)).listByStudentId(3L);
    }

    @Test
    void emptyStudent_stillStableAndConstantReads() {
        when(studentMapper.findById(4L)).thenReturn(student(4, "梁听山"));
        when(studentMapper.findByIds(anyList())).thenReturn(List.of(student(4, "梁听山")));
        when(lessonCourseMapper.findAll()).thenReturn(List.of(course(15, "C-1015", "OPEN")));
        when(prerequisiteMapper.findAll()).thenReturn(List.of());
        when(enrollmentMapper.listByStudentId(4L)).thenReturn(List.of());

        var bundle = pathService.pathsForStudent(4L);

        assertEquals(1, bundle.getPaths().size());
        assertEquals("READY", bundle.getPaths().get(0).getConclusion());
        assertEquals(List.of(), bundle.getOrphanEnrollments());
    }

    @Test
    void orphanEnrollment_missingCourse_isPlaceholderDataGap() {
        when(studentMapper.findById(1L)).thenReturn(student(1, "韩青梧"));
        when(studentMapper.findByIds(anyList())).thenReturn(List.of(student(1, "韩青梧")));
        when(lessonCourseMapper.findAll()).thenReturn(List.of(course(16, "C-1016", "OPEN")));
        when(prerequisiteMapper.findAll()).thenReturn(List.of(rel(16, 199)));
        // 学习记录指向已删除课程 199
        when(enrollmentMapper.listByStudentId(1L))
                .thenReturn(List.of(enrollment(1, 199, "LEARNING")));

        var bundle = pathService.pathsForStudent(1L);

        assertEquals(1, bundle.getOrphanEnrollments().size());
        assertEquals(199L, bundle.getOrphanEnrollments().get(0).getCourseId());
        assertTrue(bundle.getOrphanEnrollments().get(0).isPlaceholder());
        CoursePathView c16 = bundle.getPaths().get(0);
        assertEquals("DATA_GAP", c16.getConclusion());
    }

    @Test
    void guard_passesWhenPrereqCompleted() {
        stubCommon(false);
        when(enrollmentMapper.listByStudentId(1L))
                .thenReturn(List.of(enrollment(1, 7, "COMPLETED")));

        assertDoesNotThrow(() -> pathService.guardEnroll(
                student(1, "韩青梧"), List.of(course(6, "C-1006", "OPEN"))));
    }

    @Test
    void guard_blocksWhenOnlyLegacyNormalStatus() {
        stubCommon(false);
        // 旧 NORMAL 记录不解锁 6
        when(enrollmentMapper.listByStudentId(1L))
                .thenReturn(List.of(enrollment(1, 7, "NORMAL")));

        BizException ex = assertThrows(BizException.class, () -> pathService.guardEnroll(
                student(1, "韩青梧"), List.of(course(6, "C-1006", "OPEN"))));
        assertTrue(ex.getMessage().contains("最短先修路径"), ex.getMessage());
    }

    @Test
    void guard_batchCoursesCannotUnlockEachOther() {
        // 8 是无先修课；7 需 8；6 需 7。同批选 6、7、8 —— 库里没有任何 COMPLETED 记录，
        // 即使 8、7 在同一批里，也不能互相当成已完成先修，必须被拦。
        when(studentMapper.findByIds(anyList())).thenReturn(List.of(student(1, "韩青梧")));
        when(lessonCourseMapper.findAll()).thenReturn(List.of(
                course(8, "C-1008", "OPEN"),
                course(7, "C-1007", "OPEN"),
                course(6, "C-1006", "OPEN")));
        when(prerequisiteMapper.findAll()).thenReturn(List.of(rel(6, 7), rel(7, 8)));
        when(enrollmentMapper.listByStudentId(1L)).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> pathService.guardEnroll(
                student(1, "韩青梧"),
                List.of(course(6, "C-1006", "OPEN"),
                        course(7, "C-1007", "OPEN"),
                        course(8, "C-1008", "OPEN"))));
        // 最深的 6 最先被守卫拦下，缺口包含 7
        assertTrue(ex.getMessage().contains("C-1006"), ex.getMessage());
        assertTrue(ex.getMessage().contains("C-1007"), ex.getMessage());
    }

    @Test
    void guard_singleAndBatchShareSameConclusion() {
        stubCommon(false);
        when(enrollmentMapper.listByStudentId(1L)).thenReturn(List.of());

        LessonCourse target = course(6, "C-1006", "OPEN");
        BizException single = assertThrows(BizException.class,
                () -> pathService.guardEnroll(student(1, "韩青梧"), List.of(target)));
        BizException batch = assertThrows(BizException.class,
                () -> pathService.guardEnroll(student(1, "韩青梧"), List.of(target)));
        assertEquals(single.getMessage(), batch.getMessage());
    }

    @Test
    void guard_cycleMessageIsReadable() {
        when(studentMapper.findByIds(anyList())).thenReturn(List.of(student(1, "韩青梧")));
        when(lessonCourseMapper.findAll()).thenReturn(List.of(
                course(13, "C-1013", "OPEN"), course(14, "C-1014", "OPEN")));
        when(prerequisiteMapper.findAll()).thenReturn(List.of(rel(13, 14), rel(14, 13)));
        when(enrollmentMapper.listByStudentId(1L)).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> pathService.guardEnroll(
                student(1, "韩青梧"), List.of(course(13, "C-1013", "OPEN"))));
        assertTrue(ex.getMessage().contains("循环"), ex.getMessage());
        assertTrue(ex.getMessage().contains("C-1013"), ex.getMessage());
    }
}

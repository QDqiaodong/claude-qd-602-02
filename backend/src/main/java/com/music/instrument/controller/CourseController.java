package com.music.instrument.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.music.instrument.common.ApiResponse;
import com.music.instrument.domain.LessonCourse;
import com.music.instrument.domain.Student;
import com.music.instrument.dto.BatchEnrollRequest;
import com.music.instrument.dto.EnrollmentCreateRequest;
import com.music.instrument.dto.view.CourseDetailView;
import com.music.instrument.dto.view.CoursePathView;
import com.music.instrument.dto.view.EnrollmentView;
import com.music.instrument.dto.view.StudentPathBundleView;
import com.music.instrument.service.CourseService;

/**
 * 学员与课程接口。
 */
@RestController
@RequestMapping("/api")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping("/options")
    public ApiResponse<Map<String, Object>> options() {
        return ApiResponse.success(courseService.options());
    }

    @GetMapping("/students")
    public ApiResponse<List<Student>> students() {
        return ApiResponse.success(courseService.listStudents());
    }

    @GetMapping("/courses")
    public ApiResponse<List<LessonCourse>> courses() {
        return ApiResponse.success(courseService.listCourses());
    }

    /** 课程行内展开：已报名学员名单 */
    @GetMapping("/courses/{id}/detail")
    public ApiResponse<CourseDetailView> courseDetail(@PathVariable Long id) {
        return ApiResponse.success(courseService.courseDetail(id));
    }

    @GetMapping("/enrollments")
    public ApiResponse<List<EnrollmentView>> enrollments(@RequestParam(required = false) Long studentId,
                                                         @RequestParam(required = false) Long courseId) {
        return ApiResponse.success(courseService.listEnrollments(studentId, courseId));
    }

    /** 学员课程路径：一次给出该学员在全部课程上的可学/受阻结论与最短缺口路径（常数次查询）。 */
    @GetMapping("/students/{id}/course-paths")
    public ApiResponse<StudentPathBundleView> studentPaths(@PathVariable Long id) {
        return ApiResponse.success(courseService.studentPaths(id));
    }

    /** 单门课程路径：课程详情页与列表共用同一解析，刷新后结论一致。 */
    @GetMapping("/students/{studentId}/course-paths/{courseId}")
    public ApiResponse<CoursePathView> coursePath(@PathVariable Long studentId,
                                                  @PathVariable Long courseId) {
        return ApiResponse.success(courseService.coursePath(studentId, courseId));
    }

    @PostMapping("/enrollments")
    public ApiResponse<EnrollmentView> enroll(@RequestBody EnrollmentCreateRequest request) {
        return ApiResponse.success("约课成功", courseService.enroll(request));
    }

    /** 一键多科报名：一次请求批量落到 enrollment 表 */
    @PostMapping("/enrollments/batch")
    public ApiResponse<List<EnrollmentView>> batchEnroll(@RequestBody BatchEnrollRequest request) {
        return ApiResponse.success("批量约课成功", courseService.batchEnroll(request));
    }
}

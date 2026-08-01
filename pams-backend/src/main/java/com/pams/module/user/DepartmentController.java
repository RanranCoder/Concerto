package com.pams.module.user;

import com.pams.common.Result;
import com.pams.entity.Department;
import com.pams.repository.DepartmentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/depts")
public class DepartmentController {
    private final DepartmentRepository departmentRepository;
    public DepartmentController(DepartmentRepository departmentRepository) { this.departmentRepository = departmentRepository; }

    @GetMapping
    public Result<List<Department>> list() {
        return Result.ok(departmentRepository.findAll());
    }
}

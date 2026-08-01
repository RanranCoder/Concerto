package com.pams.module.user;

import com.pams.common.Result;
import com.pams.entity.Role;
import com.pams.repository.RoleRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
public class RoleController {
    private final RoleRepository roleRepository;
    public RoleController(RoleRepository roleRepository) { this.roleRepository = roleRepository; }

    @GetMapping
    public Result<List<Role>> list() {
        return Result.ok(roleRepository.findAll());
    }
}

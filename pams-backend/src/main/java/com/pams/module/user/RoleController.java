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
    private final com.pams.module.permission.service.TenantAuthorizationService service;
    public RoleController(com.pams.module.permission.service.TenantAuthorizationService service) { this.service = service; }

    @GetMapping
    public Result<?> list() {
        return Result.ok(service.list());
    }
}

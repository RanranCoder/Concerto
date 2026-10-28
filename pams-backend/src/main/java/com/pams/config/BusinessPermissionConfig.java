package com.pams.config;

import com.pams.common.BizException;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.security.LoginUser;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** Read endpoints previously guarded only by navigation must enforce business capabilities. */
@Configuration
public class BusinessPermissionConfig implements WebMvcConfigurer {
  private final TenantAuthorizationService auth;

  public BusinessPermissionConfig(TenantAuthorizationService auth) {
    this.auth = auth;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest req, HttpServletResponse res, Object handler) {
                var a = SecurityContextHolder.getContext().getAuthentication();
                if (a == null || !(a.getPrincipal() instanceof LoginUser u)) return true;
                String[] parts = req.getRequestURI().split("/");
                if (parts.length < 3 || !req.getMethod().equals("GET")) return true;
                if (parts[2].startsWith("party")
                    && !auth.business(u.getId()).contains("party:view"))
                  throw new BizException(403, "没有党务查看权限");
                String permission =
                    switch (parts[2]) {
                      case "activities" -> "activity:view";
                      case "tasks" -> "task:view";
                      case "plans" -> "plan:view";
                      case "agendas" -> "agenda:view";
                      case "scores" -> "score:view";
                      case "seats", "seat-layouts" -> "seat:view";
                      case "members", "member-sessions" -> "member:view";
                      case "party" -> "party:view";
                      case "articles", "news" -> "content:view";
                      case "schedules",
                              "activity-schedules",
                              "free-schedules",
                              "no-class-schedules" ->
                          "schedule:view";
                      case "materials" -> "material:view";
                      case "templates" -> "template:view";
                      case "credits" -> "quality:view";
                      case "group-chats", "chat" -> "chat:view";
                      case "announcements" -> "notice:view";
                      default -> null;
                    };
                if (req.getRequestURI().equals("/api/schedules/my")) permission = "schedule:self:view";
                if (permission != null && !auth.business(u.getId()).contains(permission))
                  throw new BizException(403, "没有操作权限：" + permission);
                return true;
              }
            })
        .addPathPatterns("/api/**");
  }
}

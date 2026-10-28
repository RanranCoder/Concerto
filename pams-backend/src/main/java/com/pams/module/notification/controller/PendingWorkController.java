package com.pams.module.notification.controller;
import com.pams.common.Result;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.notification.service.PendingWorkService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications/pending")
public class PendingWorkController {
  private final PendingWorkService service;
  public PendingWorkController(PendingWorkService service){this.service=service;}
  @GetMapping public Result<PendingWorkService.Summary> pending(){return Result.ok(service.summary(ToolContext.current(null,null)));}
}

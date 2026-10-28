package com.pams.module.permission.controller;

import com.pams.common.BizException;
import com.pams.common.Result;
import com.pams.module.permission.service.GovernanceDenialAudit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(-20)
@RestControllerAdvice(assignableTypes = RoleManagementController.class)
public class GovernanceExceptionAdvice {
  private final GovernanceDenialAudit audit;

  public GovernanceExceptionAdvice(GovernanceDenialAudit audit) {
    this.audit = audit;
  }

  @ExceptionHandler(BizException.class)
  public ResponseEntity<Result<Void>> rejected(BizException error, HttpServletRequest request) {
    int status =
        java.util.Set.of(401, 403, 404, 409).contains(error.getCode()) ? error.getCode() : 400;
    if (!request.getMethod().equals("GET") && java.util.Set.of(403, 409).contains(status))
      audit.rejected(request.getMethod(), request.getRequestURI(), status, error.getMessage());
    return ResponseEntity.status(status).body(Result.fail(error.getCode(), error.getMessage()));
  }
}

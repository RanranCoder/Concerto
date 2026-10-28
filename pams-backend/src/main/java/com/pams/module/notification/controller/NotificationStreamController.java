package com.pams.module.notification.controller;

import com.pams.module.notification.service.NotificationStreamRegistry;
import com.pams.security.LoginUser;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class NotificationStreamController {
  private final NotificationStreamRegistry streams;
  public NotificationStreamController(NotificationStreamRegistry streams){this.streams=streams;}
  @GetMapping(value="/api/notifications/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal LoginUser user,@RequestHeader("Authorization") String token) {
    return ResponseEntity.ok().header("Cache-Control","no-cache, no-transform")
      .header("X-Accel-Buffering","no").body(streams.open(user,token));
  }
}

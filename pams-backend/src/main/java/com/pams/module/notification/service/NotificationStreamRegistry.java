package com.pams.module.notification.service;

import com.pams.common.OrgContext;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.security.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Local connection registry only; persisted inbox remains the source of truth. */
@Service
public class NotificationStreamRegistry {
  private final JdbcTemplate db;private final JwtUtil jwt;private final TenantAuthorizationService auth;
  private final ConcurrentMap<String,Connection> connections=new ConcurrentHashMap<>();
  private final ThreadPoolExecutor writers=new ThreadPoolExecutor(2,8,60,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{
    Thread thread=new Thread(r,"notification-sse");thread.setDaemon(true);return thread;
  });
  private record Connection(long org,long user,long issuedAt,Instant expires,SseEmitter emitter,java.util.concurrent.atomic.AtomicLong writingSince) {}
  public NotificationStreamRegistry(JdbcTemplate db,JwtUtil jwt,TenantAuthorizationService auth) {this.db=db;this.jwt=jwt;this.auth=auth;}
  public synchronized SseEmitter open(LoginUser user,String header) {
    if(header==null || !header.startsWith("Bearer "))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var claims=jwt.parse(header.substring(7));
    long count=connections.values().stream().filter(c->c.user==user.getId() && c.org==user.getOrgId()).count();
    if(count>=8 || connections.size()>=2000)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    long duration=Math.min(900_000,claims.getExpiration().getTime()-System.currentTimeMillis());
    if(duration<=0)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var emitter=new SseEmitter(duration);String id=UUID.randomUUID().toString();
    var c=new Connection(user.getOrgId(),user.getId(),claims.getIssuedAt().toInstant().getEpochSecond(),claims.getExpiration().toInstant(),emitter,new java.util.concurrent.atomic.AtomicLong());
    connections.put(id,c);
    emitter.onCompletion(()->connections.remove(id,c));
    emitter.onTimeout(()->close(id,c));emitter.onError(e->close(id,c));
    if(!send(id,c,"ready"))close(id,c);
    return emitter;
  }
  private boolean valid(Connection c) {
    if(!c.expires.isAfter(Instant.now()))return false;
    var rows=db.queryForList("SELECT token_invalid_before FROM sys_user WHERE id=? AND org_id=? AND status=1 AND deleted=0",c.user,c.org);
    if(rows.isEmpty() || !auth.valid(c.user))return false;
    Object watermark=rows.getFirst().get("token_invalid_before");
    return watermark==null || c.issuedAt>=JwtUtil.epochSecond(((java.sql.Timestamp)watermark).toLocalDateTime());
  }
  private boolean send(String id,Connection c,String event) {
    try {writers.execute(()->write(id,c,event));return true;}
    catch(RejectedExecutionException e){close(id,c);return false;}
  }
  private void write(String id,Connection c,String event) {
    if(!connections.containsKey(id))return;
    Long previous=OrgContext.get();OrgContext.set(c.org);
    try {
      if(!valid(c)){close(id,c);return;}
      synchronized(c) {
        c.writingSince.set(System.currentTimeMillis());
        try {c.emitter.send(SseEmitter.event().name(event).data(Map.of("protocolVersion",1)));}
        finally {c.writingSince.set(0);}
      }
    } catch(Exception e) {close(id,c);}
    finally {if(previous==null)OrgContext.clear();else OrgContext.set(previous);}
  }
  public boolean changed(long org,long user) {
    boolean online=false;
    for(var entry:connections.entrySet())if(entry.getValue().org==org && entry.getValue().user==user)
      online=send(entry.getKey(),entry.getValue(),"inbox.changed") || online;
    return online;
  }
  @Scheduled(fixedDelay=15000)
  public void heartbeat() {connections.forEach((id,c)->{
    if(c.writingSince.get()>0 && System.currentTimeMillis()-c.writingSince.get()>10_000)close(id,c);
    else send(id,c,"heartbeat");
  });}
  private void close(String id,Connection c) {
    if(connections.remove(id,c))c.emitter.complete();
  }
  @jakarta.annotation.PreDestroy
  public void shutdown(){connections.forEach(this::close);}
  @jakarta.annotation.PreDestroy
  public void stopWriters(){writers.shutdownNow();}
  public int size(){return connections.size();}
  public long size(long org){return connections.values().stream().filter(c->c.org==org).count();}
}

package com.pams.module.notification.service;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** Explicit opt-in clone migration runner. Never accepts the production schema name. */
public final class NotificationAcceptanceMigration {
  private NotificationAcceptanceMigration() {}
  public static void requireIsolatedTarget(String marker,String url) {
    if(!"ISOLATED".equals(marker) || url==null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1:13316|mysql:3306|notification-acceptance-mysql:3306)/notification_acceptance(?:\\?.*)?"))
      throw new IllegalArgumentException("Only the explicitly isolated acceptance database is allowed");
  }
  public static void main(String[] args) throws Exception {
    String url=System.getenv("PAMS_ACCEPTANCE_JDBC_URL");requireIsolatedTarget(System.getenv("PAMS_NOTIFICATION_ACCEPTANCE"),url);
    String user=System.getenv("PAMS_ACCEPTANCE_USER"),password=System.getenv("PAMS_ACCEPTANCE_PASSWORD");
    try(var c=DriverManager.getConnection(url,user,password)) {
      if(c.getMetaData().getDatabaseMajorVersion()!=8 || !"notification_acceptance".equals(c.getCatalog()))throw new IllegalStateException("MySQL 8 isolated schema required");
      if(args.length==1 && args[0].equals("permissions")) {
        var report=com.pams.module.permission.service.PermissionMigrationCli.report(c);
        @SuppressWarnings("unchecked") var input=(List<Map<String,Object>>)report.get("roles");
        var roles=new ArrayList<Map<String,Object>>();
        for(var role:input) {
          Map<String,Object> item=new LinkedHashMap<>();
          for(String key:List.of("orgId","roleId","name","added","removed","highImpact","attendanceDecisionRequired","effectiveBefore","effectiveCandidate"))item.put(key,role.get(key));
          item.put("usersAffected",((List<?>)role.get("users")).size());roles.add(item);
        }
        System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("result","OWNER_REVIEW_REQUIRED","fingerprint",report.get("fingerprint"),"catalogHash",report.get("catalogHash"),"sourceIssues",report.get("sourceIssues"),"roles",roles,"behaviorChanges",report.get("behaviorChanges"))));
        return;
      }
      long before=count(c,"notification"),users=count(c,"sys_user");
      var flyway=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration","classpath:db/migration-mysql").load();
      String previous=flyway.info().current()==null?"empty":flyway.info().current().getVersion().getVersion();
      flyway.migrate();flyway.validate();
      if(before!=count(c,"notification") || users!=count(c,"sys_user"))throw new IllegalStateException("Migration changed source message/user counts");
      System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("result","PASS","previous",previous,"current",flyway.info().current().getVersion().getVersion(),"mysql",c.getMetaData().getDatabaseProductVersion(),"messagesPreserved",before,"usersPreserved",users)));
    }
  }
  private static long count(Connection c,String table) throws SQLException {
    try(var check=c.getMetaData().getTables(c.getCatalog(),null,table,new String[]{"TABLE"})){if(!check.next())return 0;}
    try(var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}
  }
}

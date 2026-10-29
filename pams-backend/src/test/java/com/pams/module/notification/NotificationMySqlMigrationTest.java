package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in destructive fixtures are restricted to the isolated acceptance database. */
@EnabledIfSystemProperty(named="pams.acceptance.mysql",matches="true")
class NotificationMySqlMigrationTest {
  @Test void version49HistoricalMessagesUpgradeTo56WithoutChangingIds() throws Exception {
    String base=System.getenv("PAMS_ACCEPTANCE_JDBC_URL");
    assertThat(base).startsWith("jdbc:mysql://127.0.0.1:13316/notification_acceptance?");
    String url=base.replace("/notification_acceptance?","/notification_upgrade_acceptance?");
    String password=System.getenv("SPRING_DATASOURCE_PASSWORD");
    Flyway.configure().dataSource(url,"root",password).cleanDisabled(false).load().clean();
    var config=Flyway.configure().dataSource(url,"root",password).locations("classpath:db/migration","classpath:db/migration-mysql");
    config.target("49").load().migrate();
    try(var connection=DriverManager.getConnection(url,"root",password);var s=connection.createStatement()) {
      assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);
      s.executeUpdate("INSERT INTO sys_role(org_id,code,name,level,data_scope,status) VALUES(1,'ACCEPTANCE_STAFF','迁移样本角色',1,'ALL','ACTIVE'),(1,'ACCEPTANCE_STAFF_OTHER','迁移前缀角色',1,'ALL','ACTIVE')");
      s.executeUpdate("INSERT INTO sys_user(org_id,username,password,real_name,role_id,status,deleted) SELECT 1,'notification_upgrade_one','test-only','迁移样本甲',id,1,0 FROM sys_role WHERE org_id=1 AND code='ACCEPTANCE_STAFF'");
      s.executeUpdate("INSERT INTO sys_user(org_id,username,password,real_name,role_id,status,deleted) SELECT 1,'notification_upgrade_two','test-only','迁移样本乙',id,1,0 FROM sys_role WHERE org_id=1 AND code='ACCEPTANCE_STAFF'");
      s.executeUpdate("INSERT INTO sys_user(org_id,username,password,real_name,role_id,status,deleted) SELECT 1,'notification_upgrade_prefix','test-only','前缀排除样本',id,1,0 FROM sys_role WHERE org_id=1 AND code='ACCEPTANCE_STAFF_OTHER'");
      s.executeUpdate("INSERT INTO notification(id,org_id,type,title,content,recipient_id,is_read,read_at,created_at,deleted) SELECT 900001,1,'SYSTEM','个人历史样本','migration',id,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0 FROM sys_user WHERE username='notification_upgrade_one'");
      s.executeUpdate("INSERT INTO notification(id,org_id,type,title,content,recipient_role,is_read,created_at,deleted) VALUES(900002,1,'SYSTEM','共享历史样本','migration','ACCEPTANCE_STAFF',0,CURRENT_TIMESTAMP,0)");
    }
    var migrated=Flyway.configure().dataSource(url,"root",password).locations("classpath:db/migration","classpath:db/migration-mysql").load();migrated.migrate();migrated.validate();
    assertThat(migrated.info().current().getVersion().getVersion()).isEqualTo("56");
    try(var connection=DriverManager.getConnection(url,"root",password);var s=connection.createStatement()) {
      try(var rows=s.executeQuery("SELECT COUNT(*) FROM notification WHERE id IN (900001,900002)")){rows.next();assertThat(rows.getInt(1)).isEqualTo(2);}
      try(var rows=s.executeQuery("SELECT COUNT(*) FROM notification_inbox WHERE message_id=900001 AND read_at IS NOT NULL AND archived_at IS NULL")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
      try(var rows=s.executeQuery("SELECT COUNT(*) FROM notification_inbox WHERE message_id=900002 AND archived_at IS NOT NULL")){rows.next();assertThat(rows.getInt(1)).isEqualTo(2);}
    }
  }
}

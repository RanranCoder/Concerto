package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import com.pams.module.notification.service.NotificationAcceptanceMigration;
import org.junit.jupiter.api.Test;

class NotificationAcceptanceGuardTest {
  @Test void productionOrUnmarkedTargetsAreRejectedBeforeConnecting() {
    for(String url:new String[]{"jdbc:mysql://mysql:3306/pams_db","jdbc:mysql://172.18.38.131:3306/notification_acceptance","jdbc:mysql://mysql:3306/notification_acceptance_other"})
      assertThatThrownBy(()->NotificationAcceptanceMigration.requireIsolatedTarget("ISOLATED",url)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->NotificationAcceptanceMigration.requireIsolatedTarget(null,"jdbc:mysql://mysql:3306/notification_acceptance")).isInstanceOf(IllegalArgumentException.class);
    assertThatCode(()->NotificationAcceptanceMigration.requireIsolatedTarget("ISOLATED","jdbc:mysql://notification-acceptance-mysql:3306/notification_acceptance?serverTimezone=Asia/Shanghai")).doesNotThrowAnyException();
  }
}

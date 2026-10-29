package com.pams.module.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pams.common.OrgContext;
import com.pams.module.knowledge.listener.KnowledgeIndexListener;
import com.pams.module.knowledge.service.KnowledgeIndexer;
import com.pams.module.notification.event.AnnouncementPublishedEvent;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class KnowledgeNoticeContextTest {
  @AfterEach void clear(){OrgContext.clear();}
  @Test void queuedIndexingUsesCapturedOrganizationAndRestoresWorkerContext() {
    var indexer=mock(KnowledgeIndexer.class);var queued=new AtomicReference<Runnable>();
    var listener=new KnowledgeIndexListener(indexer,queued::set);
    OrgContext.set(2L);listener.onAnnouncementPublished(new AnnouncementPublishedEvent(1L,"标题",1L));
    when(indexer.reindexSource(anyString(),anyLong())).thenAnswer(invocation->{assertThat(OrgContext.get()).isEqualTo(2L);return 0;});
    OrgContext.set(99L);queued.get().run();assertThat(OrgContext.get()).isEqualTo(99L);
    verify(indexer).reindexSource(anyString(),eq(1L));
  }
  @Test void missingOrganizationNeverFallsBackToAnotherTenant() {
    var indexer=mock(KnowledgeIndexer.class);var listener=new KnowledgeIndexListener(indexer,Runnable::run);
    OrgContext.clear();listener.onAnnouncementPublished(new AnnouncementPublishedEvent(1L,"标题",1L));
    verifyNoInteractions(indexer);
  }
}

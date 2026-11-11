package com.pams.module.agent.core;
import java.util.*;

/** Descriptive only. Authorization, transactions and limits remain in native services. */
public record ToolContractPolicy(String objectAuthorization,String resultScope,String confirmation,
                                 String retryPolicy,int modelResultCharacterBudget,String verification) {
    private static final Set<String> RECEIPT_WRITES=Set.of("preparePlanRevision","prepareTaskProgressUpdate","prepareTaskPlanChange","generateActivityTasks","sendNotification","prepareArticleRevision","prepareArticleSubmission","preparePlanSubmission","prepareSchedulePublication");
    public static ToolContractPolicy of(AgentTool tool){
        String object=switch(tool.name()){
            case "loadBusinessWorkflow"->"Static built-in guidance; no tenant data or authority changes";
            case "queryConversationActions","supplyTaskInput"->"Current user AND organization AND conversation; native tool visibility rechecked; only declared form fields; input continuation never commits business";
            case "createActivity"->"ActivityService.create within authenticated organization; native create permission; no pre-existing object";
            case "createMember"->"MemberService.create with current actor, validated session and native uniqueness; no account creation";
            case "createSeatLayout"->"Explicit activity plus visible template; SeatLayoutService.createFromTemplate with current actor";
            case "draftArticleFromActivity"->"Explicit resolved activity; ArticleService.create as current author; draft only";
            case "draftPlanSections"->"PlanService.agentCurrent/createAgentVersion; current owner and expected plan/revision";
            case "generateActivitySchedule"->"ActivityScheduleService.preview/generateReviewed; exact preview fingerprint; does not publish";
            case "previewCreditBatch"->"Activity/attendance resolved recipient set; CreditService.batchAddFromActivity with current actor";
            case "publishAnnouncement"->"AnnouncementService.create in current organization as current actor; explicit broadcast confirmation";
            case "getActivityDetail","queryActivityAgenda","queryActivityScores","queryRosterSigninStats"->"Explicit current-organization activity via native activity access; no cross-organization inference";
            case "queryActivityOverview"->"Native organization-filtered activity query; bounded returned subset, not full-source statistics";
            case "queryAnnouncements"->"Current organization native announcement query; detail read does not mark read/confirmed";
            case "findMembers","listOrgUnits"->"AgentDirectoryReadService organization/account/member visibility; ambiguous identities remain unresolved";
            case "queryMyPendingWork"->"PendingWorkService current user/role/department and current native business permissions";
            case "queryCommonFreeTime"->"CourseScheduleService.analyzeFreeTime; explicit scoped user IDs or current user; missing course data is unknown";
            case "queryAttendance"->"AttendanceManageService: management permission for all records, otherwise native current-user self scope";
            case "queryCreditRecords"->"CreditService: quality:view permits management scope, otherwise current user only";
            case "queryPartyMemberProgress"->"PartyMemberService with mandatory ctx.isStaff masking; native stage/organization filtering";
            case "queryNotifications"->"NotificationService current user/role/department; never arbitrary recipient inbox";
            case "searchMaterials"->"MaterialService metadata page in current organization/current department; metadata is not file authorization";
            case "searchArticles"->"ArticleService metadata page with current content:view and matching organization; body excluded";
            case "recallConversation"->"ConversationStore owner/current conversation; citations reauthorized on every read; history is not a receipt";
            case "readPlanDocument","listPlanVersions","preparePlanRevision","getPlanReviewStatus","preparePlanSubmission"->"PlanAccessService: current/history visibility; writer owner and baseline rechecked on confirm";
            case "readArticleDocument","getArticleReviewStatus","prepareArticleRevision","prepareArticleSubmission"->"Current organization; edit requires author/manager; revision and status rechecked on confirm";
            case "queryActivityTasks","prepareTaskProgressUpdate","prepareTaskPlanChange","generateActivityTasks"->"Current activity; native task assignee/manager and graph baseline rules";
            case "resolveNotificationAudience","sendNotification","getNotificationDeliveryStatus"->"Organization/user/conversation-bound audience or operation; native recipient rules rechecked";
            case "searchKnowledgeEvidence","readKnowledgeEvidence"->"KnowledgeQuery source authorization and egress; scoped expiring evidence handles rechecked";
            case "readMaterialDocument"->"Native material/FileService authorization, file type and canonical path containment";
            case "querySeatLayout"->"Explicit organization-scoped layout; native seat permission and baseline";
            case "queryActivitySchedules","prepareSchedulePublication"->"Organization/activity scope; explicit schedule IDs; native publication rules and per-ID baselines";
            default->"NATIVE_SERVICE_POLICY_REQUIRES_INDIVIDUAL_REVIEW";
        };
        String scope=switch(tool.name()){
            case "readArticleDocument"->"COMPLETE_SAVED_BODY_EXCLUDING_ATTACHMENTS_UP_TO_16000_CHARACTERS";
            case "readPlanDocument"->"SNAPSHOT_PAGE; ONLY_EXPLICIT_FULL_COVERAGE_ELIGIBLE_FOR_REVISION";
            case "readMaterialDocument"->"FILE_HASH_BOUND_PAGE_NOT_WHOLE_DOCUMENT";
            case "searchKnowledgeEvidence","readKnowledgeEvidence"->"AUTHORIZED_EVIDENCE_FRAGMENT_NOT_SOURCE_DOCUMENT";
            case "queryActivityScores"->"DATABASE_RECORDED_SCORE_AGGREGATES_ONLY";
            case "querySeatLayout"->"SAVED_LAYOUT_CELLS_PAGE_NOT_VERIFIED_CAPACITY";
            default->tool.writeOperation()?"PREVIEW_NOT_COMMITTED_BUSINESS_RESULT":"RETURNED_PAYLOAD_ONLY_UNLESS_NATIVE_COMPLETENESS_DECLARED";
        };
        String confirmation = switch (tool.name()) {
            case "draftArticleFromActivity", "generateActivitySchedule" ->
                    "ONE_SCOPED_CURRENT_USER_MESSAGE_OR_EXPLICIT_TOKEN; CURRENT_AUTHORIZATION";
            default -> tool instanceof PersistedInteractionTool ? "INPUT_ONLY; BUSINESS_COMMIT_REMAINS_GATED"
                    : tool.writeOperation() ? "EXPLICIT_TOKEN_AND_CURRENT_AUTHORIZATION" : "NOT_APPLICABLE";
        };
        return new ToolContractPolicy(object,scope,confirmation,
            RECEIPT_WRITES.contains(tool.name())?"RECONCILE_DURABLE_RECEIPT; NEVER_REPLAY_UNKNOWN_WRITE":tool.writeOperation()?"LEGACY_GATE; BUSINESS_IDEMPOTENCY_NOT_INDIVIDUALLY_VERIFIED":"NO_AUTOMATIC_RETRY_FROM_TEXT_ERROR",12000,
            object.startsWith("NATIVE_SERVICE")?"AUDIT_REMAINING":"SOURCE_POLICY_REVIEWED; SEE_PER_TOOL_REGRESSION_MATRIX; NOT_PRODUCTION_CERTIFICATION");
    }
    public static boolean durableReceipt(String name){return RECEIPT_WRITES.contains(name);}
}

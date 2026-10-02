package com.pams.module.knowledge.source;

import com.pams.entity.Department;
import com.pams.module.member.entity.Member;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import com.pams.repository.DepartmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 成员源（{@code MemberSource}）的**隐私边界硬断言** —— 对应方案 R17 的同一条思路。
 *
 * <h3>为什么必须有一条这样的测试</h3>
 * 成员表里同时有"组织信息"和"个人标识"两类字段：
 * <pre>
 * 收录    姓名 · 职务 · 部门 · 届别 · 状态      → "谁是组织部长"要答得出来
 * 排除    学号 · 手机号 · 政治面貌 · 备注        → 不可脱敏的个人标识
 * </pre>
 * 排除的实现方式是**根本不读这些字段**（而不是读出来再遮）——
 * "没进过索引"比"遮住了"可靠：遮罩依赖正则，正则漏一种格式就漏一批数据；
 * 而没读过的字段不可能出现在任何分块、快照或引用片段里。
 *
 * <p>这条边界的风险在于**它没有任何外在表现**：将来有人为了"多给点上下文"
 * 顺手加一句 {@code m.getPhone()}，代码编译通过、运行正常、检索结果更丰富了 ——
 * 直到有人从问答里读出手机号。所以必须用测试把它钉死。
 *
 * <p>同时断言"该收的字段确实收了"：只验证"不含敏感值"的话，
 * 把整个抽取逻辑删空也能通过。
 */
class MemberSourceSafetyTest {

    /** 这些值一旦出现在抽取结果里就是泄露 */
    private static final String SECRET_PHONE = "13800001234";
    private static final String SECRET_STUDENT_NO = "2023117788";
    private static final String SECRET_POLITICAL = "中共预备党员";
    private static final String SECRET_REMARK = "家庭住址在某某路1号";

    private MemberRepository memberRepository;
    private MemberSessionRepository sessionRepository;
    private DepartmentRepository departmentRepository;
    private MemberSource source;

    @BeforeEach
    void setUp() {
        memberRepository = Mockito.mock(MemberRepository.class);
        sessionRepository = Mockito.mock(MemberSessionRepository.class);
        departmentRepository = Mockito.mock(DepartmentRepository.class);
        source = new MemberSource(memberRepository, sessionRepository, departmentRepository);
    }

    private static Member member(long id, String name, String position) {
        Member m = new Member();
        m.setId(id);
        m.setSessionId(40L);
        m.setDeptId(7L);
        m.setName(name);
        m.setPosition(position);
        m.setStatus("ACTIVE");
        // 以下四个字段是"必须被排除"的那一类 —— 故意都填上有辨识度的值
        m.setPhone(SECRET_PHONE);
        m.setStudentNo(SECRET_STUDENT_NO);
        m.setPoliticalStatus(SECRET_POLITICAL);
        m.setRemark(SECRET_REMARK);
        return m;
    }

    private void stubRepos() {
        when(memberRepository.findAll()).thenReturn(List.of(member(11L, "张三", "DEPT_HEAD")));

        MemberSession s = new MemberSession();
        s.setId(40L);
        s.setName("第四十届");
        when(sessionRepository.findAllById(any())).thenReturn(List.of(s));
        when(sessionRepository.findById(40L)).thenReturn(java.util.Optional.of(s));

        Department d = new Department();
        d.setId(7L);
        d.setName("组织部");
        when(departmentRepository.findAllById(any())).thenReturn(List.of(d));
    }

    /** 把抽取结果拍平成一个可直接断言的字符串 */
    private String flatten(KnowledgeSource.KnowledgeDoc doc) {
        StringBuilder sb = new StringBuilder(doc.title() == null ? "" : doc.title()).append('\n');
        for (KnowledgeSource.Section s : doc.sections()) {
            sb.append(s.heading()).append('|')
              .append(s.path()).append('|')
              .append(s.chunkTitle()).append('|')
              .append(s.content()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void extractionNeverContainsPersonalIdentifiers() {
        stubRepos();

        List<KnowledgeSource.KnowledgeDoc> docs = source.extractAll();

        assertThat(docs).as("应当为每个成员产出一篇文档").hasSize(1);
        String text = flatten(docs.get(0));

        assertThat(text)
                .as("手机号绝不能出现在知识库内容里")
                .doesNotContain(SECRET_PHONE);
        assertThat(text)
                .as("学号绝不能出现在知识库内容里")
                .doesNotContain(SECRET_STUDENT_NO);
        assertThat(text)
                .as("政治面貌属敏感个人信息，不收录")
                .doesNotContain(SECRET_POLITICAL);
        assertThat(text)
                .as("备注是自由文本，可能含住址/健康等敏感信息，不收录")
                .doesNotContain(SECRET_REMARK);
    }

    /** 只验"不含敏感值"是不够的：把抽取删空也能通过。这里验该收的确实收了 */
    @Test
    void extractionKeepsOrganizationalFields() {
        stubRepos();

        String text = flatten(source.extractAll().get(0));

        assertThat(text).contains("张三").contains("部长").contains("组织部").contains("第四十届");
        assertThat(text).as("职务要出中文标签而不是枚举码 DEPT_HEAD").doesNotContain("DEPT_HEAD");
        assertThat(text).as("状态同理").doesNotContain("ACTIVE");
    }

    /**
     * T2 源的生死线：标题必须携带「姓名 · 部门 · 职务」。
     *
     * <p>T2 只参与标题匹配（不参与正文 BM25），所以标题就是它唯一的检索入口。
     * 若标题沿用文档级（"第四十届 成员名册"），用户问"组织部长是谁"永远匹配不到 ——
     * 静默失效，没有任何报错。
     */
    @Test
    void chunkTitleCarriesTheSearchableKey() {
        stubRepos();

        KnowledgeSource.Section s = source.extractAll().get(0).sections().get(0);

        assertThat(s.chunkTitle()).contains("张三").contains("组织部").contains("部长");
        assertThat(s.chunkTitle())
                .as("标题里不得混入被排除的字段")
                .doesNotContain(SECRET_PHONE).doesNotContain(SECRET_STUDENT_NO);
    }

    /** 单条抽取（增量重建路径）必须与全量抽取产出同构，否则"改了一个人"会重建出不同结果 */
    @Test
    void extractOneMatchesExtractAllShape() {
        stubRepos();
        when(memberRepository.findById(11L)).thenReturn(java.util.Optional.of(member(11L, "张三", "DEPT_HEAD")));

        List<KnowledgeSource.KnowledgeDoc> one = source.extractOne(11L);
        List<KnowledgeSource.KnowledgeDoc> all = source.extractAll();

        assertThat(one).hasSize(1);
        assertThat(one.get(0).sourceId())
                .as("sourceId 必须等于传入的 id —— 增量重建按它删旧分块，不相等就会残留")
                .isEqualTo(11L);
        assertThat(flatten(one.get(0))).isEqualTo(flatten(all.get(0)));
    }

    /** 成员已删除 → 返回空列表，索引器据此清理该成员的分块（源删则索引删） */
    @Test
    void deletedMemberYieldsEmptySoChunksGetCleaned() {
        when(memberRepository.findById(99L)).thenReturn(java.util.Optional.empty());

        assertThat(source.extractOne(99L)).isEmpty();
    }

    /** 权限口径：与 /members 路由 RequirePerm('member:view') 及 MemberController 类级注解同码 */
    @Test
    void accessRequiresMemberViewPermission() {
        assertThat(source.canAccess(ctx("member:view"), 11L)).isTrue();
        assertThat(source.canAccess(ctx("activity:view"), 11L))
                .as("没有 member:view 就不能检索成员内容")
                .isFalse();
        assertThat(source.canAccess(null, 11L))
                .as("无上下文一律拒绝（防旁路调用）")
                .isFalse();
    }

    private static com.pams.module.agent.core.ToolContext ctx(String... codes) {
        List<org.springframework.security.core.authority.SimpleGrantedAuthority> auths =
                new java.util.ArrayList<>();
        for (String c : codes) {
            auths.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(c));
        }
        return new com.pams.module.agent.core.ToolContext(null, auths, 1L, "st_test");
    }
}

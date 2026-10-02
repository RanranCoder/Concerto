package com.pams.module.knowledge.retriever;

import com.pams.module.agent.core.ToolContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索 SQL 的**参数接线**回归。
 *
 * <p><b>这个 bug 值得单独锁死</b>：{@code fulltextT1} 的 SQL 里，
 * {@code SELECT} 子句内的 {@code MATCH ... AGAINST (?)} 是**整条 SQL 中最先出现的占位符**。
 * 而权限条件是在 {@code WHERE} 里用可变数量的 {@code ?}（每个权限码一个）拼出来的。
 * 两者一旦顺序弄反 —— 查询词被绑到权限码的占位符上 ——
 * <b>检索会永远返回零命中，却不报任何错</b>（绑定的都是字符串，MySQL 不会拒绝）。
 *
 * <p>它当初就是这么漏过来的：单元测试没覆盖，直到端到端冒烟里
 * "问一个资料里明明有的问题却答『没有依据』"才暴露出来。
 * 而且它还带一个误导性：权限码为空的账号（auths 为空）刚好不会错位，
 * 所以只有"权限较多的真实角色"才会踩到 —— 这类缺陷在开发自测里极难出现。
 *
 * <p>因此本测试不验"检索准不准"（那是端到端的事），只验**结构与顺序**：
 * <ol>
 *   <li>{@code ?} 的个数必须等于绑定参数的个数（错位最常见的形态）</li>
 *   <li>{@code SELECT} 里的那个占位符必须拿到**查询词**（args[0]）</li>
 *   <li>最后一个参数必须是 limit</li>
 *   <li>权限码必须出现在查询词之后</li>
 * </ol>
 */
class ChunkSearchRepositoryTest {

    private JdbcTemplate jdbc;
    private ChunkSearchRepository repo;

    @BeforeEach
    void setUp() {
        jdbc = Mockito.mock(JdbcTemplate.class);
        repo = new ChunkSearchRepository(jdbc, new QueryTokenizer());
    }

    /** 造一个"权限很多"的上下文 —— 正是当年漏过去的场景（auths 为空时不会错位） */
    private static ToolContext ctxWithAuthorities() {
        List<SimpleGrantedAuthority> auths = new ArrayList<>();
        auths.add(new SimpleGrantedAuthority("ROLE_TEACHER"));
        for (String c : List.of("activity:view", "notice:view", "kb:read", "material:view")) {
            auths.add(new SimpleGrantedAuthority(c));
        }
        return new ToolContext(null, auths, 1L, "st_test");
    }

    @SuppressWarnings("unchecked")
    private Object[] captureArgsForFulltext(String query) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        repo.fulltextT1(query, ctxWithAuthorities(), 7);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sqlCap.capture(), any(RowMapper.class), argsCap.capture());

        String sql = sqlCap.getValue();
        Object[] args = argsCap.getValue();

        long placeholders = sql.chars().filter(ch -> ch == '?').count();
        assertThat((long) args.length)
                .as("占位符个数必须等于绑定参数个数；不等就说明接线错了。SQL=%s", sql)
                .isEqualTo(placeholders);

        return args;
    }

    @Test
    void fulltextBindsQueryToTheFirstPlaceholder() {
        String query = "青松岭联调";
        Object[] args = captureArgsForFulltext(query);

        assertThat(args[0])
                .as("SELECT 子句的 MATCH 占位符在 SQL 中最先出现，必须拿到查询词")
                .isEqualTo(query);
    }

    @Test
    void fulltextBindsLimitLast() {
        Object[] args = captureArgsForFulltext("青松岭联调");

        assertThat(args[args.length - 1])
                .as("LIMIT 的占位符在 SQL 末尾，必须是 limit 值")
                .isEqualTo(7);
    }

    @Test
    void fulltextKeepsAuthoritiesAfterTheQuery() {
        Object[] args = captureArgsForFulltext("青松岭联调");

        // args[0] = SELECT 的查询词；args[1..n] = 权限条件里的权限码；倒数第二个 = WHERE 的查询词
        assertThat(args[1]).as("权限码必须排在查询词之后").isNotEqualTo("青松岭联调");
        assertThat(args[args.length - 2])
                .as("WHERE 里的 MATCH 占位符同样拿到查询词")
                .isEqualTo("青松岭联调");
    }

    @Test
    void likeFallbackAlsoLinesUp() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        repo.likeT1("青松岭", ctxWithAuthorities(), 5);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sqlCap.capture(), any(RowMapper.class), argsCap.capture());

        long placeholders = sqlCap.getValue().chars().filter(ch -> ch == '?').count();
        assertThat((long) argsCap.getValue().length)
                .as("降级路径同样要满足占位符与参数配对")
                .isEqualTo(placeholders);
    }

    @Test
    void t2TitleQueryAlsoLinesUp() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        repo.titleMatchT2("模板", ctxWithAuthorities(), 3);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sqlCap.capture(), any(RowMapper.class), argsCap.capture());

        long placeholders = sqlCap.getValue().chars().filter(ch -> ch == '?').count();
        assertThat((long) argsCap.getValue().length).isEqualTo(placeholders);
    }
}

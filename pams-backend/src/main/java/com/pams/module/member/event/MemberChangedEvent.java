package com.pams.module.member.event;

/**
 * 成员数据变更事件（V27，供知识库增量重建使用）。
 *
 * <p><b>为什么要为它单独建一个事件</b>：知识库的索引必须跟随源（方案 §2.3），
 * 而成员是本次新接入的知识源。没有事件的话，新增/修改一个成员要等到**每日兜底重建**
 * 才会出现在知识库里 —— 用户上午加的人，下午问"谁是组织部长"仍然答不上来，
 * 而"索引陈旧"最难排查的地方就是它不报错、只是答旧内容（方案 R6）。
 *
 * <p>{@link #getMemberId()} 为 {@code null} 表示<b>批量变更</b>
 * （导入、批量删除、整届归档），此时监听器按"重建整个 member 源"处理 ——
 * 逐条发事件在导入 500 人的场景下会往队列里塞 500 个任务，
 * 把队列打满反而拖累其他请求。这是刻意的粒度取舍：单条变更精确重建，批量变更整体重建。
 */
public class MemberChangedEvent {

    private final Long memberId;

    private MemberChangedEvent(Long memberId) {
        this.memberId = memberId;
    }

    /** 单个成员新增/修改/删除 */
    public static MemberChangedEvent one(Long memberId) {
        return new MemberChangedEvent(memberId);
    }

    /** 批量变更（导入/批量删除/归档）：整源重建 */
    public static MemberChangedEvent bulk() {
        return new MemberChangedEvent(null);
    }

    /** {@code null} = 批量变更 */
    public Long getMemberId() { return memberId; }
}

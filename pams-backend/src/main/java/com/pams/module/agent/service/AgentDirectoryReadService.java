package com.pams.module.agent.service;
import com.pams.common.BizException;
import com.pams.module.agent.core.*;
import com.pams.module.agent.tool.ToolArgs;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @Transactional(readOnly=true)
public class AgentDirectoryReadService {
    private final AgentPreparedOperations ops;private final JdbcTemplate db;
    public AgentDirectoryReadService(AgentPreparedOperations ops,JdbcTemplate db){this.ops=ops;this.db=db;}
    private int page(Map<String,Object> args){int p=ToolArgs.intVal(args,"page",0);if(p<0 || p>10000)throw new BizException(400,"page超出范围");return p;}
    public ToolResult members(ToolContext ctx,Map<String,Object> args){
        ops.guard(ctx,"member:view",false);int page=page(args);String keyword=ToolArgs.str(args,"keyword","");if(keyword.length()>80)throw new BizException(400,"关键词过长");
        String where=" FROM member m WHERE m.org_id=? AND m.deleted=0";var parameters=new ArrayList<Object>();parameters.add(ctx.getLoginUser().getOrgId());
        if(!keyword.isBlank()){where+=" AND m.name LIKE ?";parameters.add("%"+keyword+"%");}
        for(String field:List.of("deptId","sessionId"))if(args.get(field)!=null){Long id=ToolArgs.lng(args,field);if(id==null)throw new BizException(400,"筛选ID无效");where+=" AND m."+(field.equals("deptId")?"dept_id":"session_id")+"=?";parameters.add(id);}
        long total=db.queryForObject("SELECT COUNT(*)"+where,Long.class,parameters.toArray());var query=new ArrayList<>(parameters);query.add(5);query.add(page*5);
        var rows=db.queryForList("SELECT m.id AS memberId,m.name,m.dept_id AS deptId,m.session_id AS sessionId,m.position,m.student_no AS studentNo"+where+" ORDER BY m.id LIMIT ? OFFSET ?",query.toArray());
        var result=new ArrayList<Map<String,Object>>();
        for(var row:rows){Object number=row.remove("studentNo");List<Map<String,Object>> account=number==null||number.toString().isBlank()?List.of():db.queryForList("SELECT id,dept_id FROM sys_user WHERE org_id=? AND student_no=? AND deleted=0 AND status=1 ORDER BY id LIMIT 2",ctx.getLoginUser().getOrgId(),number);
            result.add(ToolArgs.map("memberId",row.get("memberId"),"name",row.get("name"),"deptId",row.get("deptId"),"sessionId",row.get("sessionId"),"position",row.get("position"),
                    "userId",account.size()==1?account.getFirst().get("id"):null,"accountDeptId",account.size()==1?account.getFirst().get("dept_id"):null,"accountState",account.isEmpty()?"NO_ACTIVE_ACCOUNT":account.size()==1?"UNIQUE_ACTIVE_ACCOUNT":"AMBIGUOUS"));}
        return ToolResult.ok("已返回最小成员定位信息；memberId与userId不同，重名或账号歧义须选择，不默认首项。",ToolArgs.map("members",result,"page",page,"pageSize",5,"total",total,"hasMore",(page+1L)*5<total));
    }
    public ToolResult units(ToolContext ctx,Map<String,Object> args){
        ops.guard(ctx,"member:view",false);int page=page(args);Long org=ctx.getLoginUser().getOrgId();
        var units=db.queryForList("SELECT id,name,parent_id AS parentId,unit_type AS unitType FROM sys_department WHERE org_id=? ORDER BY id LIMIT 5 OFFSET ?",org,page*5);
        var roles=db.queryForList("SELECT id,code,name FROM sys_role WHERE org_id=? AND status='ACTIVE' ORDER BY id LIMIT 5 OFFSET ?",org,page*5);
        var positions=db.queryForList("SELECT DISTINCT position AS code FROM member WHERE org_id=? AND deleted=0 AND status='ACTIVE' ORDER BY position LIMIT 5 OFFSET ?",org,page*5);
        long departments=db.queryForObject("SELECT COUNT(*) FROM sys_department WHERE org_id=?",Long.class,org),positionCount=db.queryForObject("SELECT COUNT(DISTINCT position) FROM member WHERE org_id=? AND deleted=0 AND status='ACTIVE'",Long.class,org),roleCount=db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE org_id=? AND status='ACTIVE'",Long.class,org);
        return ToolResult.ok("已读取部门、成员岗位代码与通知受众角色标签；三类标识不同，岗位没有独立ID时不伪造ID。不含权限配置、账号或联系方式。",ToolArgs.map("units",units,"positions",positions,"audienceRoles",roles,"page",page,"pageSize",5,"unitTotal",departments,"positionTotal",positionCount,"audienceRoleTotal",roleCount,"hasMore",(page+1L)*5<Math.max(departments,Math.max(positionCount,roleCount))));
    }
}

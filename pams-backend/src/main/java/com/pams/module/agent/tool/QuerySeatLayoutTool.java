package com.pams.module.agent.tool;
import com.pams.common.BizException;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.*;
import com.pams.module.seat.repository.SeatLayoutRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Component
public class QuerySeatLayoutTool implements AgentTool {
    private final SeatLayoutRepository layouts;private final AgentPreparedOperations ops;
    public QuerySeatLayoutTool(SeatLayoutRepository layouts,AgentPreparedOperations ops){this.layouts=layouts;this.ops=ops;}
    public String name(){return "querySeatLayout";}public String label(){return "读取指定座位布局";}public String runningLabel(){return "正在读取座位布局…";}
    public String description(){return "按layoutId读取明确座位布局尺寸及登记单元格，20格每页，续读携带baseline。标签按敏感级脱敏；网格大小不推断实际可用容量，不推断缺失人员分配。";}
    public List<String> requiredPermissions(){return List.of("seat:view");}public Sensitivity sensitivity(){return Sensitivity.SENSITIVE;}
    public String toLlmText(ToolContext ctx,ToolResult result){return BoundedDocumentToolText.render(result);}
    public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map("layoutId",ToolArgs.prop("integer","明确布局ID"),"page",ToolArgs.prop("integer","从0开始"),"baseline",ToolArgs.prop("string","续读基线")),List.of("layoutId"));}
    @Transactional(readOnly=true)
    public ToolResult execute(ToolContext ctx,Map<String,Object> args){try{
        ops.guard(ctx,"seat:view",false);Long id=integer(args,"layoutId",null),page=integer(args,"page",0L);if(id==null||page>10000)throw new BizException(400,"布局ID或分页无效");
        var layout=layouts.findById(id).filter(l->Objects.equals(l.getOrgId(),ctx.getLoginUser().getOrgId())).orElseThrow(()->new BizException(404,"布局不存在或不可见"));
        String content=Objects.toString(layout.getSeatData(),"[]");if(content.length()>300000)throw new BizException(409,"布局超过读取上限，请使用原生页面");
        String baseline=PlanDocumentReader.hash(ops.encode(ToolArgs.map("id",id,"updatedAt",layout.getUpdatedAt(),"rows",layout.getRows(),"cols",layout.getCols(),"cells",content)));
        String expected=ToolArgs.str(args,"baseline");if(page>0&&expected==null||expected!=null&&!expected.equals(baseline))throw new BizException(409,"布局基线缺失或已变化，请重读首页");
        var cells=ops.json.readTree(content);if(!cells.isArray())throw new BizException(409,"布局数据格式无效");int start=(int)Math.min(page*20,cells.size()),end=Math.min(start+20,cells.size());var selected=new ArrayList<Map<String,Object>>();
        for(int i=start;i<end;i++){var cell=cells.get(i);var item=new LinkedHashMap<String,Object>();for(String key:List.of("row","col","type","label","state"))if(cell.has(key))item.put(key,ops.json.convertValue(cell.get(key),Object.class));selected.add(item);}
        return ToolResult.ok("已读取登记布局单元格，不推断缺失分配或真实可用容量。",ToolArgs.map("layoutId",id,"activityId",layout.getActivityId(),"name",layout.getName(),"rows",layout.getRows(),"cols",layout.getCols(),"baseline",baseline,"cells",selected,"totalCells",cells.size(),"page",page,"hasMore",end<cells.size(),"capacityVerified",false,"scope","SAVED_LAYOUT_CELLS","asOf",java.time.Instant.now().toString()));
    }catch(BizException e){return ToolResult.failed(e.getMessage());}catch(Exception e){return ToolResult.failed("布局无法可靠读取，请查看原生页面");}}
    private Long integer(Map<String,Object> args,String key,Long fallback){Object value=args.get(key);if(value==null)return fallback;if(!value.toString().matches("[0-9]{1,18}"))throw new BizException(400,"整数参数无效");return Long.valueOf(value.toString());}
}

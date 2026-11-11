import com.pams.module.agent.service.BudgetDocxRevision;
import com.pams.module.agent.service.PlanDocumentReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
/** Read-only format boundary probe. Document text is never executed or sent to a model. */
class InspectDocumentFormats {
    public static void main(String[] args)throws Exception{
        var cases=new ArrayList<Map<String,Object>>();
        for(int i=1;i<args.length;i++){
            var file=Path.of(args[i]);var data=Files.readAllBytes(file);var item=new LinkedHashMap<String,Object>();item.put("file",file.getFileName().toString());item.put("sha256",PlanDocumentReader.hash(data));item.put("bytes",data.length);
            try{BudgetDocxRevision.validate(data);item.put("revisionEligibleFormat",true);}catch(Exception e){item.put("revisionEligibleFormat",false);item.put("reason",e.getMessage());}
            cases.add(item);
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),Map.of("readOnly",true,"modelCalls",0,"cases",cases));
    }
}

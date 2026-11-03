package com.pams.module.agent.orchestration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ControlledBindingsTest {
    final Map<String,Object> schema=Map.of("type","object","properties",Map.of("id",Map.of("type","integer"),"name",Map.of("type","string")),"required",List.of("id"));
    Map<String,Object> contract(Object... bindings){return Map.of("goal","查42和42.5","schema",schema,"inputBindings",List.of(bindings));}
    @Test void userNumbersAreTypedAndNotExtractedFromProse(){
        assertThat(ControlledBindings.resolve(contract(Map.of("parameter","id","source","USER","quote","42")),Map.of())).containsEntry("id",42);
        assertThatThrownBy(()->ControlledBindings.resolve(contract(Map.of("parameter","id","source","USER","quote","查42")),Map.of())).hasMessageContaining("类型");
        assertThatThrownBy(()->ControlledBindings.resolve(contract(Map.of("parameter","id","source","USER","quote","42.5")),Map.of())).hasMessageContaining("类型");
        assertThatThrownBy(()->ControlledBindings.resolve(contract(Map.of("parameter","id","source","USER","quote","999")),Map.of())).hasMessageContaining("引用");
    }
    @Test void bindingUsesExactStructuredFieldNotSummaryAndRequiresRequiredArguments()throws Exception{
        var binding=Map.of("parameter","id","source","STEP","stepId",1,"path","payload.records[0].id");
        var receipt=new ObjectMapper().readTree("{\"payload\":{\"records\":[{\"id\":7}]},\"summary\":\"ID999\"}");
        assertThat(ControlledBindings.resolve(contract(binding),Map.of(1,receipt))).containsEntry("id",7);
        assertThatThrownBy(()->ControlledBindings.resolve(contract(binding),Map.of())).hasMessageContaining("回执");
        assertThatThrownBy(()->ControlledBindings.resolve(contract(),Map.of())).hasMessageContaining("必要参数");
        assertThatThrownBy(()->ControlledBindings.resolve(contract(Map.of("parameter","id","source","STEP","stepId",1,"path","summary")),Map.of(1,receipt))).hasMessageContaining("业务数据范围");
    }
    @Test void nestedSchemaRejectsUnknownFieldsAndWrongEnums(){
        var json=new ObjectMapper();var nested=Map.of("type","object","properties",Map.of("changes",Map.of("type","array","items",Map.of("type","string","enum",List.of("A")),"maxItems",2)),"required",List.of("changes"));
        assertThatThrownBy(()->ControlledBindings.validate(json.valueToTree(nested),json.valueToTree(Map.of("changes",List.of("B"))),0)).hasMessageContaining("枚举");
        assertThatThrownBy(()->ControlledBindings.validate(json.valueToTree(schema),json.valueToTree(Map.of("id",1,"privilege",true)),0)).hasMessageContaining("范围");
    }
}

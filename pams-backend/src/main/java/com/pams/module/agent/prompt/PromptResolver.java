package com.pams.module.agent.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pinned classpath resources: never accepts a model-controlled path or silently falls back. */
public final class PromptResolver {
    public enum Id {
        CORE_POLICY("core-policy"), EXECUTOR("executor"), KNOWLEDGE("knowledge"),
        REVIEWER("reviewer"), MEMORY_SUMMARY("memory-summary"), PLANNER("planner");
        private final String file;
        Id(String file) { this.file=file; }
    }
    public record Resolved(String id, String version, String sha256, String text) {}
    private static final Map<Id,Resolved> RESOURCES = loadAll();
    private PromptResolver() {}
    public static Resolved resolve(Id id) { return RESOURCES.get(Objects.requireNonNull(id)); }
    public static String text(Id id) { return resolve(id).text(); }
    private static Map<Id,Resolved> loadAll() {
        var out=new EnumMap<Id,Resolved>(Id.class);
        for(var id:Id.values()) {
            String path="/agent/prompts/v1/"+id.file+".txt";
            try(var input=PromptResolver.class.getResourceAsStream(path)) {
                if(input==null) throw new IllegalStateException("Missing prompt: "+path);
                String text=new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n").strip();
                if(text.isBlank()) throw new IllegalStateException("Empty prompt: "+path);
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
                out.put(id,new Resolved(id.file,"v1",hash,text));
            } catch(Exception e) { throw new IllegalStateException("Cannot load prompt: "+path,e); }
        }
        return Collections.unmodifiableMap(out);
    }
}

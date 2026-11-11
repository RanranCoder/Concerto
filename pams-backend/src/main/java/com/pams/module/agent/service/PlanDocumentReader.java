package com.pams.module.agent.service;

import org.apache.poi.xwpf.usermodel.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Read-only format probe. Text blocks are addresses within a hashed snapshot, not edit handles. */
public final class PlanDocumentReader {
    static final int CHUNK_LENGTH = 600;
    public record Document(List<Map<String,Object>> blocks,List<String> unsupportedElements) {}
    private PlanDocumentReader() {}
    public static String hash(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e) {throw new IllegalStateException(e);}
    }
    public static String hash(String text) {return hash(text.getBytes(StandardCharsets.UTF_8));}
    public static Document read(byte[] bytes) throws IOException {
        var blocks=new ArrayList<Map<String,Object>>();var unsupported=new TreeSet<String>();
        try(var doc=new XWPFDocument(new ByteArrayInputStream(bytes))) {
            int index=0;
            for(var element:doc.getBodyElements()) {
                String id="b"+(index++);
                if(element instanceof XWPFParagraph p) add(blocks,id,"PARAGRAPH",p.getText());
                else if(element instanceof XWPFTable t) {
                    int row=0;
                    for(var tr:t.getRows()) {
                        int col=0;
                        for(var cell:tr.getTableCells()) {
                            add(blocks,id+"r"+row+"c"+(col++),"TABLE_CELL",cell.getText());
                            if(!cell.getTables().isEmpty()) unsupported.add("NESTED_TABLE");
                        }
                        row++;
                    }
                } else unsupported.add("BODY_ELEMENT");
            }
            // Conservative exclusions: no claim that POI text is the entire Word document.
            String xml=doc.getDocument().xmlText();
            for(String tag:List.of("drawing","pict","object","sdt","ins","del","fldSimple","fldChar","altChunk","vMerge","gridSpan","txbxContent"))
                if(xml.matches("(?s).*<(?:[A-Za-z0-9_]+:)?"+tag+"(?:\\s|/?>).*")) unsupported.add(tag);
            if(!doc.getHeaderList().isEmpty() || !doc.getFooterList().isEmpty()) unsupported.add("HEADER_FOOTER");
            if(!doc.getFootnotes().isEmpty() || !doc.getEndnotes().isEmpty()) unsupported.add("NOTES");
            if(!doc.getAllPictures().isEmpty()) unsupported.add("PICTURES");
        }
        return new Document(List.copyOf(blocks),List.copyOf(unsupported));
    }
    private static void add(List<Map<String,Object>> blocks,String id,String type,String text) throws IOException {
        if(blocks.size()>=10000) throw new IOException("Document block budget exceeded");
        // Split for bounded model rendering; never silently discard part of a paragraph/cell.
        for(int start=0,part=0;start<Math.max(1,text.length());start+=CHUNK_LENGTH,part++) {
            if(blocks.size()>=10000) throw new IOException("Document block budget exceeded");
            String content=text.substring(start,Math.min(text.length(),start+CHUNK_LENGTH));
            blocks.add(Map.of("blockId",id+"p"+part,"type",type,"text",content,"hash",hash(content)));
        }
    }
}

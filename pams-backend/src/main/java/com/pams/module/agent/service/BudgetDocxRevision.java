package com.pams.module.agent.service;

import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Narrow v1: one plain text node in a paragraph-only DOCX. Unchanged ZIP parts keep exact bytes. */
public final class BudgetDocxRevision {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final Set<String> TAGS=Set.of("document","body","p","pPr","pStyle","jc","spacing","ind","r","rPr","b","i","u","color","sz","szCs","rFonts","lang","t","sectPr","pgSz","pgMar","cols","docGrid","br","tab","bCs","iCs");
    private static final Set<String> PARTS=Set.of("[Content_Types].xml","_rels/.rels","docProps/app.xml","docProps/core.xml","docProps/custom.xml",
            "word/document.xml","word/_rels/document.xml.rels","word/styles.xml","word/fontTable.xml","word/settings.xml",
            "word/webSettings.xml","word/theme/theme1.xml","word/numbering.xml");
    private BudgetDocxRevision() {}
    public static void validate(byte[] source) throws Exception {parse(parts(source).get("word/document.xml"));}
    private static Map<String,byte[]> parts(byte[] source) throws IOException {
        var result=new LinkedHashMap<String,byte[]>();int total=0;
        try(var in=new ZipInputStream(new ByteArrayInputStream(source))) {
            for(ZipEntry e;(e=in.getNextEntry())!=null;) {
                if(!PARTS.contains(e.getName())) throw new IOException("Unsupported DOCX package part: "+e.getName());
                byte[] bytes=in.readNBytes(4*1024*1024+1);total+=bytes.length;
                if(bytes.length>4*1024*1024 || total>8*1024*1024 || result.size()>100 || result.put(e.getName(),bytes)!=null)
                    throw new IOException("DOCX package budget/duplicate part");
            }
        }
        if(!result.containsKey("word/document.xml")) throw new IOException("Missing document body");
        return result;
    }
    private static Document parse(byte[] xml) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        var nodes=document.getElementsByTagName("*");
        for(int i=0;i<nodes.getLength();i++) {
            var node=nodes.item(i);
            if(!W.equals(node.getNamespaceURI()) || !TAGS.contains(node.getLocalName())) throw new IOException("Unsupported DOCX element: "+node.getLocalName());
        }
        return document;
    }
    public static byte[] replace(byte[] source,String before,String after) throws Exception {
        var parts=parts(source);var document=parse(parts.get("word/document.xml"));
        var texts=document.getElementsByTagNameNS(W,"t");Node match=null;
        for(int i=0;i<texts.getLength();i++) if(texts.item(i).getTextContent().equals(before)) {
            if(match!=null) throw new IOException("Ambiguous text node");match=texts.item(i);
        }
        if(match==null) throw new IOException("Replacement must match one complete text run");
        match.setTextContent(after);
        var factory=TransformerFactory.newInstance();factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
        var out=new ByteArrayOutputStream();factory.newTransformer().transform(new DOMSource(document),new StreamResult(out));
        parts.put("word/document.xml",out.toByteArray());out.reset();
        try(var zip=new ZipOutputStream(out)) {
            for(var entry:parts.entrySet()) {zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }
        return out.toByteArray();
    }
}

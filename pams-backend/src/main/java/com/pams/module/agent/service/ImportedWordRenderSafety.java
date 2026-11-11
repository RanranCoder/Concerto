package com.pams.module.agent.service;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks imported packages before a renderer can open them. The stored source is never rewritten. */
final class ImportedWordRenderSafety {
    private static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static final String DOCUMENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final Pattern WORD_PART = Pattern.compile(
            "word/(?:document|styles|stylesWithEffects|numbering|fontTable|settings|webSettings|header[0-9]+"
                    + "|footer[0-9]+|footnotes|endnotes|comments)\\.xml");
    private static final Pattern THEME = Pattern.compile("word/theme/theme[0-9]+\\.xml");
    private static final Pattern RASTER = Pattern.compile("word/media/[A-Za-z0-9_-]+\\.(?:png|jpg|jpeg|gif|bmp)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RELATIVE_PATH = Pattern.compile("/?[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*");
    private static final Pattern PAGE_FIELD = Pattern.compile(
            "(?:PAGE|NUMPAGES|SECTION|SECTIONPAGES)(?:\\s+\\\\\\*\\s+(?:MERGEFORMAT|Arabic))?", Pattern.CASE_INSENSITIVE);
    private static final Set<String> RELATIONSHIPS = Set.of("officeDocument", "styles", "stylesWithEffects", "numbering",
            "fontTable", "settings", "webSettings", "theme", "header", "footer", "footnotes", "endnotes", "comments",
            "image", "hyperlink", "extended-properties", "custom-properties");
    private static final Set<String> UNSAFE_ELEMENTS = Set.of("altchunk", "object", "oleobject", "control", "pict",
            "attachedtemplate", "subdoc", "mailmerge", "include", "script", "iframe", "linkedtext", "bindata");
    private static final Set<String> RESOURCE_ATTRIBUTES = Set.of("href", "src", "link", "schemalocation", "base");
    private static final Set<String> CONTENT_TYPES = Set.of(DOCUMENT_TYPE, "application/xml",
            "application/vnd.openxmlformats-package.relationships+xml",
            "application/vnd.openxmlformats-package.core-properties+xml",
            "application/vnd.openxmlformats-officedocument.extended-properties+xml",
            "application/vnd.openxmlformats-officedocument.custom-properties+xml",
            "application/vnd.openxmlformats-officedocument.theme+xml",
            "application/vnd.ms-word.styleswitheffects+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.fonttable+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.websettings+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.endnotes+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml",
            "image/png", "image/jpeg", "image/gif", "image/bmp");

    private ImportedWordRenderSafety() {}

    static boolean canRender(byte[] docx) {
        try {
            Map<String, byte[]> parts = WordBlockEdits.readParts(docx);
            if (!parts.containsKey("[Content_Types].xml") || !parts.containsKey("_rels/.rels")) return false;
            var xml = XMLInputFactory.newFactory();
            xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            xml.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
                throw new XMLStreamException("External XML resource");
            });
            long pixels = 0;
            for (var part : parts.entrySet()) {
                String name = part.getKey();
                if (RASTER.matcher(name).matches()) {
                    pixels += rasterPixels(name, part.getValue());
                    if (pixels > 32_000_000) return false;
                } else {
                    if (!allowedXmlPart(name) || !inspectXml(xml, name, part.getValue(), parts)) return false;
                }
            }
            return true;
        } catch (IOException | XMLStreamException | RuntimeException failure) {
            return false;
        }
    }

    private static boolean allowedXmlPart(String name) {
        if (name.equals("[Content_Types].xml") || name.equals("_rels/.rels")
                || name.equals("docProps/core.xml") || name.equals("docProps/app.xml") || name.equals("docProps/custom.xml")
                || WORD_PART.matcher(name).matches() || THEME.matcher(name).matches()) return true;
        if (!name.startsWith("word/_rels/") || !name.endsWith(".rels")) return false;
        return WORD_PART.matcher("word/" + name.substring("word/_rels/".length(), name.length() - ".rels".length())).matches();
    }

    private static boolean inspectXml(XMLInputFactory xml, String name, byte[] bytes, Map<String, byte[]> parts)
            throws XMLStreamException {
        var reader = xml.createXMLStreamReader(new ByteArrayInputStream(bytes));
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.PROCESSING_INSTRUCTION
                        || event == XMLStreamConstants.ENTITY_REFERENCE) return false;
                if (event != XMLStreamConstants.START_ELEMENT) continue;
                String element = reader.getLocalName();
                String lower = element.toLowerCase(Locale.ROOT);
                if (UNSAFE_ELEMENTS.contains(lower)) return false;
                if (name.endsWith(".rels") && element.equals("Relationship")
                        && (!REL_NS.equals(reader.getNamespaceURI())
                        || !relationship(name, reader.getAttributeValue(null, "TargetMode"), reader.getAttributeValue(null, "Target"),
                        reader.getAttributeValue(null, "Type"), parts))) return false;
                if (name.equals("[Content_Types].xml") && (element.equals("Default") || element.equals("Override"))) {
                    String type = reader.getAttributeValue(null, "ContentType");
                    if (type == null || !CONTENT_TYPES.contains(type.toLowerCase(Locale.ROOT))) return false;
                }
                for (int index = 0; index < reader.getAttributeCount(); index++) {
                    String attr = reader.getAttributeLocalName(index).toLowerCase(Locale.ROOT);
                    String value = reader.getAttributeValue(index);
                    if (RESOURCE_ATTRIBUTES.contains(attr) && !value.isBlank()) return false;
                    if (attr.equals("instr") && !pageField(value)) return false;
                }
                if (lower.equals("instrtext") && !pageField(reader.getElementText())) return false;
            }
            return true;
        } finally {
            reader.close();
        }
    }

    private static boolean pageField(String code) {
        return code != null && PAGE_FIELD.matcher(code.strip()).matches();
    }

    private static boolean relationship(String part, String mode, String target, String type, Map<String, byte[]> parts) {
        if (mode != null && !mode.equals("Internal") || target == null || !RELATIVE_PATH.matcher(target).matches()
                || type == null || !relationshipType(type)) return false;
        String local = target.startsWith("/") ? target.substring(1) : target;
        for (String segment : local.split("/")) {
            if (segment.equals(".") || segment.equals("..")) return false;
        }
        String base = target.startsWith("/") || part.equals("_rels/.rels") ? "" : "word/";
        return parts.containsKey(base + local);
    }

    private static boolean relationshipType(String type) {
        return type.equals(REL_NS + "/metadata/core-properties")
                || type.startsWith(OFFICE_REL) && RELATIONSHIPS.contains(type.substring(OFFICE_REL.length()));
    }

    private static long rasterPixels(String name, byte[] bytes) throws IOException {
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (extension.equals("jpg")) extension = "jpeg";
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Unsupported image");
            var reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase(extension)) throw new IOException("Image type mismatch");
                reader.setInput(stream, true, true);
                return (long) reader.getWidth(0) * reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        }
    }
}

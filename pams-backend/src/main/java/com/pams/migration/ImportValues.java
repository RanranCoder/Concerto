package com.pams.migration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.TreeMap;

final class ImportValues {
    private static final ObjectMapper JSON = new ObjectMapper().enable(
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ImportValues() {}

    static Object read(ResultSet row, int index, ImportSchema.Column column, boolean mysql)
            throws SQLException, java.io.IOException {
        String raw = row.getString(index);
        if (raw == null) return null;
        return switch (column.type()) {
            case "smallint" -> Short.valueOf(raw);
            case "integer" -> Integer.valueOf(raw);
            case "bigint" -> Long.valueOf(raw);
            case "numeric" -> new BigDecimal(raw).stripTrailingZeros();
            case "boolean" -> switch (raw) {
                case "1", "t", "true" -> true;
                case "0", "f", "false" -> false;
                default -> throw new IllegalArgumentException("Boolean must be 0 or 1");
            };
            case "date" -> LocalDate.parse(raw);
            case "time without time zone" -> LocalTime.parse(raw);
            case "timestamp without time zone" -> LocalDateTime.parse(raw.replace(' ', 'T'));
            case "timestamp with time zone" -> mysql
                    ? LocalDateTime.parse(raw.replace(' ', 'T')).atOffset(ZoneOffset.UTC)
                    : row.getObject(index, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC);
            case "jsonb" -> JSON.writeValueAsString(canonicalJson(JSON.readTree(raw)));
            case "character varying", "text", "character" -> {
                if (raw.indexOf(0) >= 0) throw new IllegalArgumentException("Text contains a zero byte");
                yield raw;
            }
            default -> throw new IllegalArgumentException("Unsupported PostgreSQL type " + column.type());
        };
    }

    private static JsonNode canonicalJson(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(field -> fields.put(field.getKey(), canonicalJson(field.getValue())));
            return JSON.createObjectNode().setAll(fields);
        }
        if (node.isArray()) {
            var values = JSON.createArrayNode();
            node.forEach(value -> values.add(canonicalJson(value)));
            return values;
        }
        if (node.isNumber()) return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        if (node.isTextual() && node.textValue().indexOf(0) >= 0) {
            throw new IllegalArgumentException("JSON string contains a zero byte");
        }
        return node;
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static void add(MessageDigest digest, Object value) {
        byte[] data = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(data == null ? -1 : data.length).array());
        if (data != null) digest.update(data);
    }

    static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }
}

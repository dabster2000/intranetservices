package dk.trustworks.intranet.aggregates.bonus.individual.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic JSON canonicalization used only for proof and snapshot hashing. */
@ApplicationScoped
public class IndividualBonusCanonicalizer {

    @Inject ObjectMapper mapper;

    public String canonicalize(Object value) {
        try {
            return mapper.writeValueAsString(sort(mapper.valueToTree(value)));
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Could not canonicalize individual bonus payload");
        }
    }

    public String canonicalizeMap(Map<String, ?> values) {
        return canonicalize(new TreeMap<>(values));
    }

    public String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node == null || node.isNull()) return JsonNodeFactory.instance.nullNode();
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
            fields.forEach((key, value) -> sorted.set(key, sort(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> sorted.add(sort(value)));
            return sorted;
        }
        if (node.isBigDecimal() || node.isFloatingPointNumber()) {
            BigDecimal decimal = node.decimalValue().stripTrailingZeros();
            if (decimal.signum() == 0) decimal = BigDecimal.ZERO;
            if (decimal.scale() < 0) decimal = decimal.setScale(0);
            return JsonNodeFactory.instance.numberNode(decimal);
        }
        return node;
    }
}

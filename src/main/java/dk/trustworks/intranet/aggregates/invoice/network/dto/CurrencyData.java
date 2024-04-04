package dk.trustworks.intranet.aggregates.invoice.network.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

public class CurrencyData {
    private Map<String, Map<String, Double>> data;

    @JsonCreator
    public CurrencyData(@JsonProperty("data") Map<String, Map<String, Double>> data) {
        this.data = data;
    }

    public Map<String, Map<String, Double>> getData() {
        return data;
    }

    public void setData(Map<String, Map<String, Double>> data) {
        this.data = data;
    }

    @JsonAnySetter
    public void setDynamicProperty(String key, Map<String, Double> value) {
        if (data == null) {
            data = new HashMap<>();
        }
        data.put(key, value);
    }

    public Double getExchangeRate(String date, String currency) {
        Map<String, Double> dateMap = data.get(date);
        if (dateMap != null) {
            return dateMap.get(currency);
        }
        return null;
    }
}

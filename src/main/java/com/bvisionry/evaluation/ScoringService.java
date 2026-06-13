package com.bvisionry.evaluation;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class ScoringService {

    /**
     * Derive maturity label from score and threshold config.
     * Thresholds: {"Emerging": [0, 59], "Strong": [60, 79], "Elite": [80, 100]}
     */
    public String deriveMaturityLabel(BigDecimal score, Map<String, List<Integer>> thresholds) {
        int scoreInt = score.intValue();
        for (Map.Entry<String, List<Integer>> entry : thresholds.entrySet()) {
            List<Integer> range = entry.getValue();
            if (scoreInt >= range.get(0) && scoreInt <= range.get(1)) {
                return entry.getKey();
            }
        }
        return "Unknown";
    }
}

package com.bvisionry.aiengine.eval;

/**
 * One labeled evaluation case for the model-eval harness: a known assessment
 * input and the score band a correct evaluation should land in. The band (not an
 * exact score) is the contract — AI scoring is not deterministic, but a
 * trustworthy model should stay within a sane range for a known input.
 */
public record GoldenCase(
        String name,
        String rubric,
        String assessmentXml,
        int expectedMin,
        int expectedMax
) {}

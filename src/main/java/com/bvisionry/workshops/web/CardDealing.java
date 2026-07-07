package com.bvisionry.workshops.web;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Random, side-balanced dealing of a SORT task's card pool — shared by the
 * learner runner (lazy deal on task start) and the admin assignments surface
 * (deal upfront to every team).
 */
final class CardDealing {

    private static final Random RANDOM = new SecureRandom();

    private CardDealing() {
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cards(Map<String, Object> cfg) {
        Object raw = cfg.get("cards");
        return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /**
     * Deal a random, side-balanced hand: {@code dealPerTeam} cards, half from
     * each pile (backfilled from the other side when one runs short).
     */
    static List<String> deal(Map<String, Object> cfg) {
        List<Map<String, Object>> pool = cards(cfg);
        int per = intOf(cfg.get("dealPerTeam"), 0);
        List<String> all = pool.stream().map(c -> str(c.get("id"))).toList();
        if (per <= 0 || per >= all.size()) {
            return all;
        }
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        for (Map<String, Object> c : pool) {
            ("right".equals(c.get("correct")) ? right : left).add(str(c.get("id")));
        }
        Collections.shuffle(left, RANDOM);
        Collections.shuffle(right, RANDOM);
        int takeRight = Math.min(per / 2, right.size());
        int takeLeft = Math.min(per - takeRight, left.size());
        takeRight = Math.min(per - takeLeft, right.size());
        List<String> hand = new ArrayList<>(left.subList(0, takeLeft));
        hand.addAll(right.subList(0, takeRight));
        Collections.shuffle(hand, RANDOM);
        return hand;
    }

    static List<String> strList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(CardDealing::str).toList();
        }
        return List.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }
}

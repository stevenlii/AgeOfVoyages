package com.ageofvoyages.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 组合式叙事器：把叙述拆成多个槽位，每个槽位一池词组，配合“近期用词互斥”，
 * 让同一种遭遇反复出现时，描述与内心 OS 也能不停变化，不会有重复感。
 * 用法：narrator.slot("pirateApproach", "甲词", "乙词", ...) —— 短期内不会重复同一个词，直到整池轮完一轮。
 */
public final class Narrator {

    private final Map<String, Rotator> slots = new HashMap<>();

    public String slot(String key, String... options) {
        Rotator r = slots.get(key);
        if (r == null) {
            r = new Rotator(List.of(options));
            slots.put(key, r);
        }
        return r.next();
    }

    /** 槽位轮转器：全池轮转完之前不重复；整池都出现过才清零重来 */
    private static final class Rotator {
        private final List<String> pool;
        private final Deque<Integer> recent = new ArrayDeque<>();

        Rotator(List<String> pool) {
            this.pool = pool;
        }

        String next() {
            if (pool.isEmpty()) return "";
            if (pool.size() == 1) return pool.get(0);
            Integer chosen = null;
            for (int i = 0; i < pool.size(); i++) {
                if (!recent.contains(i)) { chosen = i; break; }
            }
            if (chosen == null) {
                recent.clear();
                chosen = (int) (Math.random() * pool.size());
            }
            recent.addLast(chosen);
            while (recent.size() > pool.size() - 1) recent.removeFirst();
            return pool.get(chosen);
        }
    }
}
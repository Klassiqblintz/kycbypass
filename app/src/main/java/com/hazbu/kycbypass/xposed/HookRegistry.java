package com.hazbu.kycbypass.xposed;

import java.lang.reflect.AccessibleObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for standalone-mode hooks.
 *
 * When XposedBridge is not present, each hook is stored here with its
 * target (Method/Constructor) and the XC_MethodHook. A wrapper class
 * can look up the correct hook at call time.
 */
public class HookRegistry {

    private static final Map<String, Entry> registry = new ConcurrentHashMap<>();

    public static void register(AccessibleObject target, XC_MethodHook hook) {
        String key = target.toString();
        registry.put(key, new Entry(target, hook));
    }

    public static XC_MethodHook getHook(AccessibleObject target) {
        String key = target.toString();
        Entry entry = registry.get(key);
        return entry != null ? entry.hook : null;
    }

    public static boolean isEmpty() {
        return registry.isEmpty();
    }

    private static class Entry {
        final AccessibleObject target;
        final XC_MethodHook hook;

        Entry(AccessibleObject target, XC_MethodHook hook) {
            this.target = target;
            this.hook = hook;
        }
    }
}

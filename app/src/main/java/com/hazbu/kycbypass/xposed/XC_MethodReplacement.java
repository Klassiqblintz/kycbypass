package com.hazbu.kycbypass.xposed;

import java.lang.reflect.AccessibleObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirror of Xposed's XC_MethodReplacement — replaces a method entirely
 * with a custom implementation.
 */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    /**
     * Replace the hooked method with this implementation.
     */
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    @Override
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            Object result = replaceHookedMethod(param);
            param.setResult(result);
        } catch (Throwable t) {
            param.thrown = t;
            throw t;
        }
    }

    /**
     * Convenience factory — replaces a method with a lambda-like block.
     *
     * Usage:
     *   XC_MethodReplacement.implementation((param) -> {
     *       // return replacement value
     *       return null;
     *   });
     */
    public static XC_MethodReplacement implementation(Implementation impl) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                return impl.execute(param);
            }
        };
    }

    /**
     * Convenience factory — no-arg replacement.
     */
    public static XC_MethodReplacement constant(final Object value) {
        return implementation((param) -> value);
    }

    /**
     * Functional interface for lambda-style replacements.
     */
    @FunctionalInterface
    public interface Implementation {
        Object execute(MethodHookParam param) throws Throwable;
    }
}

package com.hazbu.kycbypass.xposed;

/**
 * Base class for method hooks — mirrors Xposed's XC_MethodHook.
 *
 * When XposedBridge is present, each subclass instance is passed directly
 * to XposedBridge.hookMethod(). When absent, our standalone mode stores it
 * in HookRegistry and calls these methods via a wrapper.
 */
public abstract class XC_MethodHook {

    /**
     * Called before the hooked method executes.
     *
     * @param param The hook parameters — contains thisObject, args, setResult, etc.
     */
    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
        // No-op — subclasses override
    }

    /**
     * Called after the hooked method executes (or after beforeHookedMethod
     * if the method was replaced via setResult).
     *
     * @param param The hook parameters
     */
    protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
        // No-op — subclasses override
    }

    /**
     * Cancel the original method execution and set a custom result.
     * Call this in beforeHookedMethod to replace the method entirely.
     */
    public static void replaceMethod(XC_MethodHook.MethodHookParam param, Object result) {
        param.setResult(result);
    }

    /**
     * Parameters passed to beforeHookedMethod / afterHookedMethod.
     */
    public static class MethodHookParam {
        public Object thisObject;       // The object the method was called on (null for static)
        public Object[] args;           // The arguments passed to the method
        public Object result;           // The result (set in afterHookedMethod or from setResult)
        public Throwable thrown;        // Exception thrown by the method (null if no exception)
        public boolean hasResult = false;

        /**
         * Set the return value and skip the original method.
         * Call this in beforeHookedMethod to replace the method entirely.
         */
        public void setResult(Object result) {
            this.result = result;
            this.hasResult = true;
        }

        /**
         * Get the number of arguments the method was called with.
         */
        public int getArgs() {
            return args != null ? args.length : 0;
        }

        /**
         * Get an argument by index.
         */
        public Object getArg(int index) {
            return args != null && index < args.length ? args[index] : null;
        }

        /**
         * Set an argument by index.
         */
        public void setArg(int index, Object value) {
            if (args != null && index < args.length) {
                args[index] = value;
            }
        }
    }
}

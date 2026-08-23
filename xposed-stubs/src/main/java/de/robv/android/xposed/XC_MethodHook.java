package de.robv.android.xposed;

import java.lang.reflect.Member;

public class XC_MethodHook {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
    }

    /**
     * Compile-only ABI stub matching the classic Xposed/LSPosed API return type.
     * The implementation is provided by LSPosed at runtime and is never packaged.
     */
    public class Unhook {
        public Member getHookedMethod() {
            return null;
        }

        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        public void unhook() {}
    }
}

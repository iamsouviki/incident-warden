package com.company.mcp.tenant;

/**
 * TenantContext — spec §2 "Multi-Tenant Architecture".
 *
 * Stores the current request's tenantId in a {@link ThreadLocal} so every
 * component in the same thread can retrieve it without passing it explicitly.
 *
 * Usage:
 * <pre>
 *   // set (done automatically by TenantInterceptor)
 *   TenantContext.set("00000000-0000-0000-0000-000000000001");
 *
 *   // read anywhere in the same thread
 *   String tid = TenantContext.get();
 *
 *   // clear — ALWAYS call in a finally block or after request processing
 *   TenantContext.clear();
 * </pre>
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() { /* utility class */ }

    /** Sets the tenant ID for the current thread. */
    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    /**
     * Returns the tenant ID for the current thread, or {@code null} if not set.
     */
    public static String get() {
        return CURRENT.get();
    }

    /**
     * Clears the tenant ID from the current thread.
     * Must be called at the end of each request to avoid leaks in thread pools.
     */
    public static void clear() {
        CURRENT.remove();
    }
}

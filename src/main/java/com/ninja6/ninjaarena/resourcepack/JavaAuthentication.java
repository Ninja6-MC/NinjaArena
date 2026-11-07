package com.ninja6.ninjaarena.resourcepack;

/** Whether, and why, Java account UUIDs can be trusted as an identity for saved texture choices. */
public enum JavaAuthentication {
    ONLINE_MODE(true, "online mode"),
    ONLINE_PROXY(true, "online proxy"),
    DECLARED_LOGIN_GATE(true, "operator-declared login gate"),
    NONE(false, "not authenticated: choices last one connection");

    private final boolean authenticated;
    private final String reason;

    JavaAuthentication(boolean authenticated, String reason) {
        this.authenticated = authenticated;
        this.reason = reason;
    }

    public boolean authenticated() {
        return authenticated;
    }

    /** Server online mode wins, then an online proxy, then the operator's declared login gate. */
    public static JavaAuthentication resolve(boolean onlineMode, boolean onlineProxy, boolean declaredLoginGate) {
        if (onlineMode) {
            return ONLINE_MODE;
        }
        if (onlineProxy) {
            return ONLINE_PROXY;
        }
        return declaredLoginGate ? DECLARED_LOGIN_GATE : NONE;
    }

    /** One phrase for status and the startup line. */
    public String describe() {
        return authenticated ? "authenticated (" + reason + ")" : reason;
    }
}

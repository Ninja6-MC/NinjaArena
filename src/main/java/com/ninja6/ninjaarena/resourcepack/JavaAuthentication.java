package com.ninja6.ninjaarena.resourcepack;

import com.ninja6.ninjaarena.message.Messages;

/** Whether, and why, Java account UUIDs can be trusted as an identity for saved texture choices. */
public enum JavaAuthentication {
    ONLINE_MODE(true, "online-mode"),
    ONLINE_PROXY(true, "online-proxy"),
    DECLARED_LOGIN_GATE(true, "login-gate"),
    NONE(false, "none");

    private final boolean authenticated;
    /** The {@code pack.accounts} message naming the reason. */
    private final String key;

    JavaAuthentication(boolean authenticated, String key) {
        this.authenticated = authenticated;
        this.key = key;
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
        String reason = Messages.text("pack.accounts." + key);
        return authenticated ? Messages.text("pack.accounts.authenticated", "reason", reason) : reason;
    }
}

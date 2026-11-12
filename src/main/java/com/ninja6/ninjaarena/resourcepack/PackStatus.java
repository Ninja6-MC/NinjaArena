package com.ninja6.ninjaarena.resourcepack;

import static com.ninja6.ninjaarena.message.Messages.text;

/** Active startup settings and the current Bedrock policy, without reading or changing files. */
public final class PackStatus {
    private PackStatus() { }

    public static String describe(ResourcePackSettings java, String bedrock, JavaAuthentication accounts) {
        return describe(java, bedrock, accounts, java.textureMenu().label());
    }

    /** {@code menu} names the texture screen in use, which can differ from the setting after a fallback. */
    public static String describe(ResourcePackSettings java, String bedrock, JavaAuthentication accounts,
            String menu) {
        String state = text(!java.enabled() ? "pack.java-disabled" : java.required() ? "pack.java-required"
                : "pack.java-optional");
        return text("pack.status-line", "java", state, "accounts", accounts.describe(), "bedrock", bedrock,
                "menu", menu);
    }
}

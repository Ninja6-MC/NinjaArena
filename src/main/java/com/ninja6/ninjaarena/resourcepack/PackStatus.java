package com.ninja6.ninjaarena.resourcepack;

/** Active startup settings and the current Bedrock policy, without reading or changing files. */
public final class PackStatus {
    private PackStatus() { }

    public static String describe(ResourcePackSettings java, String bedrock, JavaAuthentication accounts) {
        return describe(java, bedrock, accounts, java.textureMenu().label());
    }

    /** {@code menu} names the texture screen in use, which can differ from the setting after a fallback. */
    public static String describe(ResourcePackSettings java, String bedrock, JavaAuthentication accounts,
            String menu) {
        String state = !java.enabled() ? "disabled" : java.required() ? "enabled, required" : "enabled, optional";
        return "Resource packs: Java: " + state + "; Java accounts: " + accounts.describe() + "; Bedrock: " + bedrock
                + "; Texture menu: " + menu;
    }
}

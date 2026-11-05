package com.ninja6.ninjaarena.portal;

import org.bukkit.World;

/**
 * What fills the opening of a player-built portal. {@link PortalCurtains} decides when a
 * surface is drawn; an implementation only draws it. The particle curtain needs no resource
 * pack; a textured surface can take its place behind this interface.
 *
 * <p>Server thread only.
 */
public interface PortalSurface {

    /**
     * Draws the portal's surface once. Called a few times a second for each portal with a
     * player nearby, so it must stay cheap and bounded whatever the opening's size.
     */
    void draw(World world, Portal portal);

    /**
     * The portal is gone, or no player has been near it for a while. A surface that keeps
     * something in the world for a portal, such as display entities, removes it here; drawing
     * again after this starts over.
     */
    default void hide(Portal portal) {
    }

    /** The plugin is disabling: hide every portal still shown. */
    default void close() {
    }
}

package net.prason.xaeronav;

import org.apache.logging.log4j.Logger;

import org.apache.logging.log4j.LogManager;

/** Mod-wide identifier and logger. Per-loader startup code lives in {@code net.prason.xaeronav.platform}. */
public final class XaeroNav {

    public static final String MOD_ID = "xaeronav";
    public static final Logger LOGGER = LogManager.getLogger();

    private XaeroNav() {
    }
}

package net.prason.xaeronav.config;

/**
 * Where the config is stored.
 *
 * <p>On NeoForge this is {@code ModConfigSpec} (FML takes care of reading, writing, and even watching the file);
 * on Fabric we read and write night-config TOML ourselves. Both use the same file location and format
 * ({@code config/xaeronav-client.toml}).
 */
public interface NavConfigStore {

    NavConfigSpec spec();

    /** Call once, after every entry has been declared. Only then is the file read and defaults filled in. */
    void build();

    /** Writes changes to disk. */
    void save();
}

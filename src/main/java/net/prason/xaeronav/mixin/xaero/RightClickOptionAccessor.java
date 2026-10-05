package net.prason.xaeronav.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import xaero.map.gui.dropdown.rightclick.RightClickOption;

/**
 * Identifies right-click menu entries by translation key. {@code getDisplayName()} returns {@code String} in
 * discontinued Xaero versions (World Map 1.39.x for 1.21.6, 1.21.7 and 1.21.9), whose descriptor differs from the
 * current {@code Component} one, so it cannot be called. The {@code name} field holding the key stays {@code String} in every version.
 */
@Mixin(RightClickOption.class)
public interface RightClickOptionAccessor {

    @Accessor(value = "name", remap = false)
    String xaeronav$translationKey();
}

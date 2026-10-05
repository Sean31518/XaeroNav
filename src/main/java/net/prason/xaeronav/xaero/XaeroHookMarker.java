package net.prason.xaeronav.xaero;

/**
 * Marker that a Xaero integration mixin applied. One mixin per injection target class implements this.
 *
 * <p>When a mixin doesn't apply, no exception or warning is raised ({@code xaeronav-xaero.mixins.json} is
 * required=false). Users only see "no line on the map", with no clue to tell whether the cause is the Xaero
 * version or configuration. This happens with new Xaero versions that change the target's shape, so
 * "the integrated mod is loaded but the target class lacks this marker" is used to detect the silent failure.
 */
public interface XaeroHookMarker {
}

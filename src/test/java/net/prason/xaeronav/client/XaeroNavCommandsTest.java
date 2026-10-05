package net.prason.xaeronav.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Checks only the command tree's syntax boundaries. Executing {@code goto}/{@code version}/{@code hooks}
 * etc. (going as far as {@code execute}) is out of scope because it touches real Minecraft/loader state
 * such as {@code PathfindingState.INSTANCE} and {@code ModList}; {@code parse} alone validates just the
 * syntax without touching them (in line with xaeronav.common.gradle.kts's policy of "keeping tests to
 * what works without bootstrapping the registries").
 */
class XaeroNavCommandsTest {

    private static final class FakeSink implements NavCommandSink {
        final List<Component> successes = new ArrayList<>();
        final List<Component> failures = new ArrayList<>();

        @Override
        public void success(Component message) {
            successes.add(message);
        }

        @Override
        public void failure(Component message) {
            failures.add(message);
        }
    }

    private static CommandDispatcher<Object> newDispatcher() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        dispatcher.register(XaeroNavCommands.tree(ctx -> new FakeSink(),
                (ctx, name) -> BlockPos.ZERO));
        return dispatcher;
    }

    @Test
    void mapdataAcceptsTheDefaultAndMaximumRadius() {
        CommandDispatcher<Object> dispatcher = newDispatcher();
        Object source = new Object();
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug mapdata", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug mapdata 128", source));
    }

    @Test
    void mapdataRejectsARadiusBeyondTheUpperBound() {
        CommandDispatcher<Object> dispatcher = newDispatcher();
        Object source = new Object();
        // IntegerArgumentType's range check doesn't apply during parse itself; it only becomes a
        // CommandSyntaxException on execute. mapdata's actual work (reading the Xaero map) is never
        // reached: this boundary check always rejects before the command body, so it's safe to execute.
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("xaeronav debug mapdata 129", source));
    }

    @Test
    void mapdataRejectsARadiusOfZero() {
        CommandDispatcher<Object> dispatcher = newDispatcher();
        Object source = new Object();
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("xaeronav debug mapdata 0", source));
    }

    @Test
    void everySubcommandParsesWithoutThrowing() {
        CommandDispatcher<Object> dispatcher = newDispatcher();
        Object source = new Object();
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav clear", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav version", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug hooks", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug summary", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav goto 0 64 0", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug route 0 64 0", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug corridor 0 64 0", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug probe 0 64 0", source));
        assertDoesNotThrow(() -> dispatcher.parse("xaeronav debug flight 0 64 0", source));
    }
}

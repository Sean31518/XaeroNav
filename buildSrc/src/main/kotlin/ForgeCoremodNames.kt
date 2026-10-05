import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Rewrites SRG names that Forge 1.16.5 mods hold as strings (in 1.16 this includes class names, which are the same as MCP names) to dev-environment names (Mojang names).
 *
 * <p>Production Forge 1.16.5 runs on SRG names so this is fine there, but Loom's dev environment runs on Mojang names, and Loom's remap only rewrites
 * type and member references in class files. Xaero uses strings in two places, and left as-is, dev runs (runClient) crash.
 * <ul>
 *   <li>coremod (JavaScript) injection targets: `ToggleableKeyBinding`, `func_151470_d` -> `NoClassDefFoundError` at startup</li>
 *   <li>reflective `Class.forName("net.minecraft.client.renderer.RenderType$Type")` -> `ClassNotFoundException` on entering a world</li>
 * </ul>
 * Inside class files, only strings that are a class name in their entirety are rewritten (member names are looked up by Forge's `ObfuscationReflectionHelper`).
 *
 * <p>Rewrites the jar in place. A rewritten jar has no SRG names left, so calling this repeatedly gives the same result.
 *
 * @param tinyWithSrg Loom's `mappings-srg.tiny` (with `srg` and `named` namespaces)
 * @return the number of files rewritten
 */
fun rewriteForgeCoremodNames(jar: Path, tinyWithSrg: Path): Int {
    val names = SrgToNamed.load(tinyWithSrg)
    var rewritten = 0
    FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), emptyMap<String, Any>()).use { zip ->
        val files = Files.walk(zip.getPath("/")).use { paths ->
            paths.filter { it.toString().endsWith(".js") || it.toString().endsWith(".class") }.toList()
        }
        for (file in files) {
            if (file.toString().endsWith(".js")) {
                val before = Files.readString(file)
                val after = names.rewrite(before)
                if (after != before) {
                    Files.writeString(file, after)
                    rewritten++
                }
            } else {
                val after = names.rewriteClassNameStrings(Files.readAllBytes(file))
                if (after != null) {
                    Files.write(file, after)
                    rewritten++
                }
            }
        }
    }
    return rewritten
}

private class SrgToNamed(private val classes: Map<String, String>, private val members: Map<String, String>) {

    fun rewrite(script: String): String {
        val withClasses = CLASS_NAME.replace(script) { match -> mapClass(match.value) ?: match.value }
        return MEMBER_NAME.replace(withClasses) { match -> members[match.value] ?: match.value }
    }

    /** Rewrites string constants that are a Minecraft class name in their entirety. Returns `null` if nothing changed. */
    fun rewriteClassNameStrings(bytes: ByteArray): ByteArray? {
        val reader = ClassReader(bytes)
        var changed = false
        // Replacing string constants doesn't change frames or max stack, so don't recompute them (no COMPUTE_*)
        val writer = ClassWriter(reader, 0)
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                override fun visitLdcInsn(value: Any?) {
                    val mapped = (value as? String)?.let { exactClass(it) }
                    if (mapped != null) {
                        changed = true
                        super.visitLdcInsn(mapped)
                    } else {
                        super.visitLdcInsn(value)
                    }
                }
            }
        }, 0)
        return if (changed) writer.toByteArray() else null
    }

    private fun exactClass(name: String): String? {
        if (!name.startsWith("net.minecraft.") && !name.startsWith("net/minecraft/")) {
            return null
        }
        val named = classes[name.replace('.', '/')] ?: return null
        return if (name.contains('.')) named.replace('/', '.') else named
    }

    /** Can be followed by more, as in `a.b.C.method`, so trim from the end until it hits the mapping table. */
    private fun mapClass(name: String): String? {
        val dotted = name.contains('.')
        var candidate = name.replace('.', '/')
        while (true) {
            classes[candidate]?.let { named ->
                val rest = name.substring(candidate.length)
                return (if (dotted) named.replace('/', '.') else named) + rest
            }
            val cut = candidate.lastIndexOf('/')
            if (cut <= "net/minecraft".length) {
                return null
            }
            candidate = candidate.substring(0, cut)
        }
    }

    companion object {
        private val CLASS_NAME = Regex("""net[./]minecraft[./][\w$./]*[\w$]""")
        private val MEMBER_NAME = Regex("""\b(?:func|field)_\d+_[A-Za-z]+_?\b""")

        fun load(tiny: Path): SrgToNamed {
            val lines = Files.readAllLines(tiny)
            val header = lines.first().split('\t')
            check(header[0] == "tiny" && header[1] == "2") { "not tiny v2: $tiny" }
            val namespaces = header.drop(3)
            val srg = namespaces.indexOf("srg")
            val named = namespaces.indexOf("named")
            check(srg >= 0 && named >= 0) { "missing srg and named namespaces: $tiny ($namespaces)" }
            val classes = HashMap<String, String>()
            val members = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val columns = line.split('\t')
                when {
                    columns[0] == "c" -> classes[columns[1 + srg]] = columns[1 + named]
                    // Method and field lines are indented one level under the class: `m`/`f`, descriptor, then the name per namespace
                    columns.size > 3 && columns[0].isEmpty() && (columns[1] == "m" || columns[1] == "f") ->
                        members[columns[3 + srg]] = columns[3 + named]
                }
            }
            return SrgToNamed(classes, members)
        }
    }
}

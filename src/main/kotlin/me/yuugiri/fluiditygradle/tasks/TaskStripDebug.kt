package me.yuugiri.fluiditygradle.tasks

import me.yuugiri.fluiditygradle.utils.toBytes
import me.yuugiri.fluiditygradle.utils.toClassNode
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.tree.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

abstract class TaskClassPatching : TaskGrouped() {

    @get:Internal
    protected abstract val patcher: (ClassNode) -> Unit

    @TaskAction
    fun execute() {
        val rootDir = File(project.buildDir, "libs")
        rootDir.listFiles()?.forEach {
            if (it.name.endsWith(".jar")) {
                logger.info("Patching ${it.absolutePath}...")
                patchJar(it, patcher)
            }
        }
    }
}

open class TaskStripDebug : TaskClassPatching() {

    override val patcher: (ClassNode) -> Unit = { patchClass(it) }
}

open class TaskStripDebugAndReobfuscate : TaskReobfuscateArtifact() {

    override fun additionalRuns(klass: ClassNode) {
        patchClass(klass)
    }
}

fun patchJar(file: File, patchClass: (ClassNode) -> Unit) {
    val jis = ZipInputStream(ByteArrayInputStream(file.readBytes())) // prevent file overwrite
    val jos = ZipOutputStream(FileOutputStream(file))
    jos.setLevel(9)
    val buffer = ByteArray(1024)
    while (true) {
        val entry = jis.nextEntry ?: break
        if (entry.isDirectory) continue
        var body: ByteArray
        run {
            val bos = ByteArrayOutputStream()
            var n: Int
            while (jis.read(buffer).also { n = it } != -1) {
                bos.write(buffer, 0, n)
            }
            bos.close()
            body = bos.toByteArray()
        }
        if (entry.name.endsWith(".class")) {
            val klass = toClassNode(body)
            patchClass(klass)
            body = toBytes(klass)
        }
        jos.putNextEntry(JarEntry(entry.name))
        jos.write(body)
        jos.closeEntry()
    }
    jis.close()
    jos.close()
}

private fun patchClass(klass: ClassNode) {
    klass.methods.forEach { patchMethod(it) }
    klass.sourceDebug = null
    klass.sourceFile = null

    klass.visibleAnnotations?.filterNotNull()?.forEach {
        if (it.desc.equals("Lkotlin/Metadata;")) {
            klass.visibleAnnotations.remove(it)
        }
    }
}

private fun patchMethod(method: MethodNode) {
    val insnList = method.instructions
    val inst = insnList.toArray()
    insnList.clear()
    var count = 0
    inst.forEach {
        if (it is LineNumberNode) {
            return@forEach
        } else if (it is MethodInsnNode && it.owner == "kotlin/jvm/internal/Intrinsics" && it.desc.contains("Ljava/lang/String;)")) {
            val lastNode = insnList.last
            if (lastNode is LdcInsnNode) {
                lastNode.cst = "${count++}"
            }
        }
        insnList.add(it)
    }
    method.localVariables?.clear()
    method.parameters?.clear()
}
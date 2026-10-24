import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.jar.*;

/** Build-time only: preserve upstream logic while replacing unsupported executor API calls. */
public final class AndroidApiRewriter {
    public static void main(String[] args) throws Exception {
        int[] replaced = {0};
        try (JarInputStream input = new JarInputStream(Files.newInputStream(Path.of(args[0])));
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            for (JarEntry entry; (entry = input.getNextJarEntry()) != null;) {
                byte[] bytes = input.readAllBytes();
                if (entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(0);
                    int before = replaced[0];
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                                    if (owner.equals("java/util/concurrent/Executors") && method.equals("newVirtualThreadPerTaskExecutor") && desc.equals("()Ljava/util/concurrent/ExecutorService;")) {
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/shengzimao/reader/AndroidExecutors", "newWorkerPool", desc, false);
                                        replaced[0]++;
                                    } else if (owner.equals("java/util/concurrent/ExecutorService") && method.equals("close") && desc.equals("()V")) {
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/shengzimao/reader/AndroidExecutors", "close", "(Ljava/util/concurrent/ExecutorService;)V", false);
                                        replaced[0]++;
                                    } else super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                                }
                            };
                        }
                    }, 0);
                    if (replaced[0] > before) {
                        bytes = writer.toByteArray();
                        System.out.println("ANDROID_EXECUTOR_PATCH " + entry.getName());
                    }
                }
                output.putNextEntry(new JarEntry(entry.getName()));
                output.write(bytes);
                output.closeEntry();
            }
        }
        if (replaced[0] == 0) throw new IllegalStateException("Expected upstream executor references were not found; review compatibility patch.");
        System.out.println("ANDROID_EXECUTOR_CALLS_REPLACED " + replaced[0]);
    }
}

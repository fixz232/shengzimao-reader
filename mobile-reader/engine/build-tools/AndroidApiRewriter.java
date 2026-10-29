import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.jar.*;

/** Build-time only: preserve upstream logic while replacing unsupported executor API calls. */
public final class AndroidApiRewriter {
    public static void main(String[] args) throws Exception {
        int[] replaced = {0};
        int[] exits = {0};
        int[] searchUrls = {0};
        int[] searchFailures = {0};
        try (JarInputStream input = new JarInputStream(Files.newInputStream(Path.of(args[0])));
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            for (JarEntry entry; (entry = input.getNextJarEntry()) != null;) {
                byte[] bytes = input.readAllBytes();
                if (entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    int before = replaced[0];
                    int beforeExits = exits[0];
                    int beforeSearchUrls = searchUrls[0];
                    int beforeSearchFailures = searchFailures[0];
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                                final java.util.Set<Label> failureHandlers = new java.util.HashSet<>();
                                boolean captureFailure;
                                @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                                    if (reader.getClassName().equals("com/pcdd/sonovel/parser/SearchParser") &&
                                            (name.equals("parse") || name.equals("getSearchResults")) && "java/lang/Exception".equals(type))
                                        failureHandlers.add(handler);
                                    super.visitTryCatchBlock(start, end, handler, type);
                                }
                                @Override public void visitLabel(Label label) {
                                    super.visitLabel(label);
                                    if (failureHandlers.contains(label)) captureFailure = true;
                                }
                                @Override public void visitVarInsn(int opcode, int variable) {
                                    if (captureFailure && opcode == Opcodes.ASTORE) {
                                        // Preserve the upstream catch behavior while surfacing the swallowed
                                        // exception to this source worker. Frames retain the same stack shape.
                                        super.visitInsn(Opcodes.DUP);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/shengzimao/reader/AndroidSearchRequests",
                                            "recordFailure", "(Ljava/lang/Throwable;)V", false);
                                        captureFailure = false;
                                        searchFailures[0]++;
                                    }
                                    super.visitVarInsn(opcode, variable);
                                }
                                @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                                    if (owner.equals("java/util/concurrent/Executors") && method.equals("newVirtualThreadPerTaskExecutor") && desc.equals("()Ljava/util/concurrent/ExecutorService;")) {
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/shengzimao/reader/AndroidExecutors", "newWorkerPool", desc, false);
                                        replaced[0]++;
                                    } else if (owner.equals("java/util/concurrent/ExecutorService") && method.equals("close") && desc.equals("()V")) {
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/shengzimao/reader/AndroidExecutors", "close", "(Ljava/util/concurrent/ExecutorService;)V", false);
                                        replaced[0]++;
                                    } else if (owner.equals("java/lang/System") && method.equals("exit") && desc.equals("(I)V")) {
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/shengzimao/reader/AndroidExecutors", "preventProcessExit", desc, false);
                                        exits[0]++;
                                    } else if (reader.getClassName().equals("com/pcdd/sonovel/parser/SearchParser") && name.equals("processUrl") &&
                                            owner.equals("java/lang/String") && method.equals("formatted") && desc.equals("([Ljava/lang/Object;)Ljava/lang/String;")) {
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/shengzimao/reader/AndroidSearchRequests", "formatUrl",
                                            "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", false);
                                        searchUrls[0]++;
                                    } else super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                                }
                            };
                        }
                    }, 0);
                    if (replaced[0] > before || exits[0] > beforeExits || searchUrls[0] > beforeSearchUrls || searchFailures[0] > beforeSearchFailures) {
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
        if (exits[0] == 0) throw new IllegalStateException("Expected upstream System.exit references were not found; review process-exit guard.");
        System.out.println("ANDROID_PROCESS_EXIT_CALLS_REPLACED " + exits[0]);
        if (searchUrls[0] != 1) throw new IllegalStateException("Expected one literal search URL formatter; review keyword encoding patch.");
        System.out.println("ANDROID_SEARCH_URL_CALLS_REPLACED " + searchUrls[0]);
        if (searchFailures[0] != 2) throw new IllegalStateException("Expected two swallowed search exception handlers; review failure reporting patch.");
        System.out.println("ANDROID_SEARCH_FAILURE_HANDLERS_CAPTURED " + searchFailures[0]);
    }
}

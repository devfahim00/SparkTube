import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Audit + patch tool for NewPipeExtractor jars.
 *
 * Problem: URLDecoder.decode(String, Charset) / URLEncoder.encode(String, Charset) are
 * Java 10 APIs that exist on Android only from API level 33. NewPipeExtractor calls them
 * (Utils.decodeUrlUtf8 etc.), crashing with NoSuchMethodError on Android 10 (API 29) and
 * below. Core library desugaring does not cover java.net, and v0.26.5 is the latest
 * extractor release, so we patch the bytecode at build/commit time.
 *
 * Fix: rewrite every such INVOKESTATIC to call AndroidUrlCompat.decodeUtf8/encodeUtf8,
 * which use the decode/encode(String, String) overloads available since API level 1.
 * Stack at the call site is [.., String, Charset] -> POP the Charset, then invoke the
 * single-String helper. Behavior parity: IllegalArgumentException on malformed input,
 * identical '+' handling, UTF-8 guaranteed.
 *
 * Modes:
 *   --audit <jar>...                     dump every java.* method referenced from bytecode
 *   --methods <jar> [classPrefix]        dump declared methods of classes matching prefix
 *   --patch <in.jar> <out.jar> [helper]  rewrite bad call sites; appends helper .class if given
 */
public final class AuditPatch {

    private static final String DECODE_CS =
            "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;";
    private static final String ENCODE_CS =
            "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;";
    private static final String STR_ONLY = "(Ljava/lang/String;)Ljava/lang/String;";
    private static final String HELPER_OWNER =
            "org/schabi/newpipe/extractor/utils/AndroidUrlCompat";
    private static final String HELPER_PATH =
            "org/schabi/newpipe/extractor/utils/AndroidUrlCompat.class";

    private interface ClassSink {
        void accept(String entryName, byte[] bytes) throws IOException;
    }

    public static void main(final String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: AuditPatch --audit <jar>... | --methods <jar> [prefix]"
                    + " | --patch <in.jar> <out.jar> [helper.class]");
            System.exit(2);
        }
        final String mode = args[0];
        if ("--audit".equals(mode)) {
            for (int i = 1; i < args.length; i++) {
                audit(Paths.get(args[i]));
            }
        } else if ("--methods".equals(mode)) {
            methods(Paths.get(args[1]), args.length > 2 ? args[2] : "");
        } else if ("--who".equals(mode)) {
            who(Paths.get(args[1]), args[2], args[3], args.length > 4 ? args[4] : "");
        } else if ("--patch".equals(mode)) {
            patch(Paths.get(args[1]), Paths.get(args[2]),
                    args.length > 3 ? Paths.get(args[3]) : null);
        } else {
            System.err.println("unknown mode " + mode);
            System.exit(2);
        }
    }

    // ---------------------------------------------------------------------- who

    private static void who(final Path jar, final String owner, final String name,
            final String descPart) throws IOException {
        System.out.println("#### WHO calls " + owner + "." + name
                + (descPart.isEmpty() ? "" : " desc~" + descPart) + "  in " + jar);
        forEachClass(jar, (entry, bytes) -> {
            final ClassReader cr = new ClassReader(bytes);
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(final int access, final String mname,
                        final String mdesc, final String signature,
                        final String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(final int opcode, final String o,
                                final String n, final String d, final boolean isInterface) {
                            if (owner.equals(o) && name.equals(n) && d.contains(descPart)) {
                                System.out.println("    " + entry);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
        });
        System.out.println();
    }

    // ------------------------------------------------------------------ audit

    private static void audit(final Path jar) throws IOException {
        System.out.println("#### AUDIT " + jar);
        final TreeMap<String, TreeSet<String>> refs = new TreeMap<>();
        forEachClass(jar, (name, bytes) -> scanRefs(bytes, refs));
        for (final Map.Entry<String, TreeSet<String>> e : refs.entrySet()) {
            System.out.println(e.getKey());
            for (final String m : e.getValue()) {
                System.out.println("    " + m);
            }
        }
        System.out.println();
    }

    private static void scanRefs(final byte[] bytes,
            final TreeMap<String, TreeSet<String>> refs) {
        final ClassReader cr = new ClassReader(bytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                    final String descriptor, final String signature,
                    final String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner,
                            final String name, final String descriptor,
                            final boolean isInterface) {
                        if (owner.startsWith("java/")) {
                            refs.computeIfAbsent(owner, k -> new TreeSet<>())
                                    .add(name + " " + descriptor);
                        }
                    }
                };
            }
        }, 0);
    }

    // ----------------------------------------------------------------- methods

    private static void methods(final Path jar, final String prefix) throws IOException {
        forEachClass(jar, (name, bytes) -> {
            if (!name.startsWith(prefix)) {
                return;
            }
            final ClassReader cr = new ClassReader(bytes);
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(final int version, final int access, final String cname,
                        final String signature, final String superName,
                        final String[] interfaces) {
                    System.out.println(cname);
                }

                @Override
                public MethodVisitor visitMethod(final int access, final String name,
                        final String descriptor, final String signature,
                        final String[] exceptions) {
                    System.out.println("    " + name + " " + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        });
    }

    // ------------------------------------------------------------------- patch

    private static void patch(final Path in, final Path out, final Path helperClass)
            throws IOException {
        final int[] total = {0};
        final List<String> patchedClasses = new ArrayList<>();
        try (JarFile jf = new JarFile(in.toFile());
                OutputStream os = Files.newOutputStream(out);
                JarOutputStream jos = new JarOutputStream(os)) {
            final Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                final JarEntry e = en.nextElement();
                final byte[] raw = readAll(jf.getInputStream(e));
                final byte[] outBytes;
                if (e.getName().endsWith(".class")
                        && !"module-info.class".equals(e.getName())
                        && needsPatch(raw)) {
                    final int[] count = {0};
                    outBytes = rewrite(raw, count);
                    if (count[0] > 0) {
                        patchedClasses.add(e.getName() + "  (" + count[0] + " call sites)");
                        total[0] += count[0];
                    }
                } else {
                    outBytes = raw;
                }
                final JarEntry je = new JarEntry(e.getName());
                jos.putNextEntry(je);
                jos.write(outBytes);
                jos.closeEntry();
            }
            if (helperClass != null) {
                final JarEntry je = new JarEntry(HELPER_PATH);
                jos.putNextEntry(je);
                jos.write(Files.readAllBytes(helperClass));
                jos.closeEntry();
            }
        }
        System.out.println("patched call sites: " + total[0]);
        for (final String c : patchedClasses) {
            System.out.println("  " + c);
        }
    }

    private static boolean needsPatch(final byte[] bytes) {
        final boolean[] found = {false};
        final ClassReader cr = new ClassReader(bytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                    final String descriptor, final String signature,
                    final String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner,
                            final String name, final String descriptor,
                            final boolean isInterface) {
                        if (isBad(opcode, owner, name, descriptor)) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static boolean isBad(final int opcode, final String owner, final String name,
            final String descriptor) {
        if (opcode != Opcodes.INVOKESTATIC) {
            return false;
        }
        if ("java/net/URLDecoder".equals(owner) && "decode".equals(name)
                && DECODE_CS.equals(descriptor)) {
            return true;
        }
        return "java/net/URLEncoder".equals(owner) && "encode".equals(name)
                && ENCODE_CS.equals(descriptor);
    }

    private static byte[] rewrite(final byte[] in, final int[] counter) {
        final ClassReader cr = new ClassReader(in);
        // COMPUTE_MAXS only: frames are copied verbatim and remain valid because the
        // transformation ([.., url, charset] -> POP -> invoke(url) -> [.., result])
        // preserves the stack shape at every basic block boundary.
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                    final String descriptor, final String signature,
                    final String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, descriptor,
                        signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner,
                            final String name, final String descriptor,
                            final boolean isInterface) {
                        if (isBad(opcode, owner, name, descriptor)) {
                            // stack: [.., url, charset] -> drop the Charset argument
                            visitInsn(Opcodes.POP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                                    "java/net/URLDecoder".equals(owner)
                                            ? "decodeUtf8" : "encodeUtf8",
                                    STR_ONLY, false);
                            counter[0]++;
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------ shared

    private static void forEachClass(final Path jar, final ClassSink sink) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            final Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                final JarEntry e = en.nextElement();
                final String n = e.getName();
                if (!n.endsWith(".class") || "module-info.class".equals(n)) {
                    continue;
                }
                sink.accept(n, readAll(jf.getInputStream(e)));
            }
        }
    }

    private static byte[] readAll(final InputStream is) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }
}

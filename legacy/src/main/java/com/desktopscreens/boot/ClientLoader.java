package com.desktopscreens.boot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;

/**
 * Loads the client's code (com.desktopscreens.client), core and Concentus, with JNA 5 from our own jar.
 *
 * <p>Minecraft 1.12.2 brings JNA 4.4.0 and JNA's platform classes of 3.4.0, and the game's class loader finds those
 * before anything in a mod's jar. core needs JNA 5 (5.14.0, which it was written and tested against), and JNA can't be
 * renamed into our own package: its native part looks for its classes by their names. So JNA 5 comes as two jars
 * inside ours (desktopscreens/lib), copied to the temp folder and loaded by a class loader of their own; the classes
 * that use it are loaded by this one, from our jar, and find JNA there. Everything else (Minecraft, Forge, the rest of
 * our mod) comes from the game's class loader as usual, so the client's code works with the very same classes as the
 * game.
 *
 * <p>Classes the game loads itself must never name the client's code: they'd get a second copy of it, which would
 * find the old JNA. They reach it through {@link ClientHooks}. The build checks this (checkClientIsolation).
 */
final class ClientLoader extends ClassLoader {
    private static final String[] OWN = {"com.desktopscreens.client.", "com.desktopscreens.core.", "com.desktopscreens.lib."};
    private static final String JNA = "com.sun.jna.";
    private static final String JNA_FILES = JNA.replace('.', '/');
    private static final String JARS = "desktopscreens/lib/";

    static {
        registerAsParallelCapable();
    }

    private final ClassLoader game;
    private final ClassLoader jna;

    private ClientLoader(ClassLoader game, ClassLoader jna) {
        super(game);
        this.game = game;
        this.jna = jna;
    }

    /** Loads the client's code and returns its entry point. */
    static ClientHooks start() throws Exception {
        ClassLoader game = ClientLoader.class.getClassLoader();
        // No parent besides Java itself: JNA needs nothing else, and must not find the game's copy.
        ClassLoader jna = new URLClassLoader(new URL[] {copy("jna"), copy("jna-platform")}, null);
        ClientLoader loader = new ClientLoader(game, jna);
        return (ClientHooks) Class.forName("com.desktopscreens.client.ClientMain", true, loader).newInstance();
    }

    private static boolean own(String name) {
        for (String prefix : OWN) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (name.startsWith(JNA)) c = jna.loadClass(name);
                else if (own(name)) c = findClass(name);
                else c = game.loadClass(name);
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    /** One of ours: the same bytes the game's loader would read (in the game, already in Forge's names). */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes;
        try (InputStream in = game.getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) throw new ClassNotFoundException(name);
            bytes = readAll(in);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        return defineClass(name, bytes, 0, bytes.length, ClientLoader.class.getProtectionDomain());
    }

    @Override
    public URL getResource(String name) {
        return name.startsWith(JNA_FILES) ? jna.getResource(name) : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return name.startsWith(JNA_FILES) ? jna.getResources(name) : super.getResources(name);
    }

    /**
     * Copies one of the jars in desktopscreens/lib to the temp folder (once: the name says what's inside) and returns
     * where it is. Copies left by other versions of the mod are removed when nothing uses them any more.
     */
    private static URL copy(String name) throws IOException {
        byte[] bytes;
        try (InputStream in = ClientLoader.class.getResourceAsStream("/" + JARS + name + ".jar")) {
            if (in == null) throw new FileNotFoundException(JARS + name + ".jar is missing from the mod's jar");
            bytes = readAll(in);
        }
        File dir = new File(System.getProperty("java.io.tmpdir"), "desktopscreens");
        File file = new File(dir, name + "-" + digest(bytes) + ".jar");
        if (!(file.isFile() && file.length() == bytes.length)) {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't make " + dir);
            File part = File.createTempFile(name + "-", ".part", dir);
            try {
                Files.write(part.toPath(), bytes);
                Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Another game started at the same moment may have put it there first, and now holds it open.
                if (!(file.isFile() && file.length() == bytes.length)) throw e;
            } finally {
                Files.deleteIfExists(part.toPath());
            }
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                // Fails while another game still uses it; then it goes next time.
                if (f.getName().matches(name + "-[0-9a-f]{16}\\.jar") && !f.equals(file)) f.delete();
            }
        }
        return file.toURI().toURL();
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 8; i++) s.append(String.format("%02x", hash[i]));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}

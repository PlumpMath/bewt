package boot;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.channels.FileChannel;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import org.projectodd.shimdandy.ClojureRuntimeShim;

public class App {
    private static File[]                  podjars    = null;
    private static File                    bootdir    = null;
    private static File                    aetherfile = null;
    private static HashMap<String, File[]> depsCache  = null;
    
    private static final String appversion = "2.0.0";
    private static final String apprelease = "r1";
    private static final String depversion = appversion + "-SNAPSHOT";
    private static final String aetherjar  = "aether-" + depversion + "-standalone.jar";

    public static ClojureRuntimeShim core   = null;
    public static ClojureRuntimeShim aether = null;

    public static ClojureRuntimeShim getCore()    { return core; }
    public static ClojureRuntimeShim getAether()  { return aether; }
    public static File               getBootDir() { return bootdir; }
    public static String             getVersion() { return appversion; }
    public static String             getRelease() { return apprelease; }
    
    private static FileLock
    getLock(File f) throws Exception {
        File lockfile = new File(f.getPath() + ".lock");
        return (new RandomAccessFile(lockfile, "rw")).getChannel().lock(); }

    private static HashMap<String, File[]>
    seedCache(ClojureRuntimeShim a) throws Exception {
        if (depsCache != null) return depsCache;
        else {
            if (a == null) {
                ensureResourceFile(aetherjar, aetherfile);
                a = newShim(new File[] { aetherfile }); }
        
            HashMap<String, File[]> cache = new HashMap<String, File[]>();
        
            cache.put("boot/pod",    resolveDepJars(a, "boot/pod"));
            cache.put("boot/core",   resolveDepJars(a, "boot/core"));
            cache.put("boot/aether", resolveDepJars(a, "boot/aether"));

            return depsCache = cache; }}
    
    private static Object
    validateCache(Object cache) throws Exception {
        for (File[] fs : ((HashMap<String, File[]>) cache).values())
            for (File f : fs)
                if (! f.exists()) throw new Exception("dep jar doesn't exist");
        return cache; }

    private static void
    writeCache(File f, Object m) throws Exception {
        FileLock         lock = getLock(f);
        FileOutputStream file = new FileOutputStream(f);
        try { (new ObjectOutputStream(file)).writeObject(m); }
        finally { file.close(); lock.release(); }}
    
    private static Object
    readCache(File f) throws Exception {
        FileLock lock = getLock(f);
        try {
            long max = 18 * 60 * 60 * 1000;
            long age = System.currentTimeMillis() - f.lastModified();
            if (age > max) throw new Exception("cache age exceeds TTL");
            return validateCache((new ObjectInputStream(new FileInputStream(f))).readObject()); }
        catch (Throwable e) {
            System.err.println("boot: updating dependencies...");
            return seedCache(null); }
        finally { lock.release(); }}
    
    public static ClojureRuntimeShim
    newShim(File[] jarFiles) throws Exception {
        URL[] urls = new URL[jarFiles.length];
        
        for (int i=0; i<jarFiles.length; i++) urls[i] = jarFiles[i].toURI().toURL();
        
        ClassLoader cl = new URLClassLoader(urls, App.class.getClassLoader());
        ClojureRuntimeShim rt = ClojureRuntimeShim.newRuntime(cl);

        rt.require("boot.pod");
        rt.invoke("boot.pod/seal-app-classloader");

        return rt; }
    
    public static ClojureRuntimeShim
    newPod() throws Exception { return newShim(podjars); }
    
    public static ClojureRuntimeShim
    newPod(File[] jarFiles) throws Exception {
        File[] files = new File[jarFiles.length + podjars.length];
        
        for (int i=0; i<podjars.length; i++) files[i] = podjars[i];
        for (int i=0; i<jarFiles.length; i++) files[i + podjars.length] = jarFiles[i];
        
        return newShim(files); }
    
    public static void
    extractResource(String resource, File outfile) throws Exception {
        ClassLoader  cl  = Thread.currentThread().getContextClassLoader();
        InputStream  in  = cl.getResourceAsStream(resource);
        OutputStream out = new FileOutputStream(outfile);
        int          n   = 0;
        byte[]       buf = new byte[4096];

        try { while ((n = in.read(buf)) > 0) out.write(buf, 0, n); }
        finally { in.close(); out.close(); }}
    
    public static void
    ensureResourceFile(String r, File f) throws Exception {
        if (! f.exists()) extractResource(r, f); }
    
    public static File[]
    resolveDepJars(ClojureRuntimeShim shim, String sym) {
        shim.require("boot.aether");
        return (File[]) shim.invoke("boot.aether/resolve-dependency-jars", sym, depversion); }
    
    public static void
    main(String[] args) throws Exception {
        File homedir = new File(System.getProperty("user.home"));
        bootdir      = new File(homedir, ".boot");
        File jardir  = new File(new File(bootdir, "lib"), apprelease);
        aetherfile   = new File(jardir, aetherjar);
        
        final File cachedir  = new File(new File(bootdir, "cache"), apprelease);
        final File cachefile = new File(cachedir, "deps.cache");
        
        jardir.mkdirs();
        cachedir.mkdirs();
        
        final HashMap<String, File[]> cache = (HashMap<String, File[]>) readCache(cachefile);

        podjars = cache.get("boot/pod");
        
        final ExecutorService ex = Executors.newCachedThreadPool();

        final Future f1 = ex.submit(new Callable() {
                public Object call() throws Exception {
                    return newShim(cache.get("boot/aether")); }});

        final Future f2 = ex.submit(new Callable() {
                public Object call() throws Exception {
                    return newShim(cache.get("boot/core")); }});
        
        final Future f3 = ex.submit(new Callable() {
                public Object call() throws Exception {
                    writeCache(cachefile, seedCache((ClojureRuntimeShim) f1.get()));
                    return null; }});
        
        Thread shutdown = new Thread() { public void run() { ex.shutdown(); }};
        Runtime.getRuntime().addShutdownHook(shutdown);

        aether = (ClojureRuntimeShim) f1.get();
        core   = (ClojureRuntimeShim) f2.get();

        core.require("boot.main");
        core.invoke("boot.main/-main", args);
        
        System.exit(0); }}
